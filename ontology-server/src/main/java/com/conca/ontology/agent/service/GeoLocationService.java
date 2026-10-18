package com.conca.ontology.agent.service;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.maxmind.geoip2.DatabaseReader;
import com.maxmind.geoip2.model.CityResponse;
import com.maxmind.geoip2.record.Location;

import jakarta.annotation.PreDestroy;

/**
 * 客户端 IP 地理位置服务：get_location 工具的定位后端。
 *
 * 定位链（IP 来源由 AgentChatService 经 RuntimeContext 以 CTX_CLIENT_IP_KEY 注入）：
 * 内网/回环 → 静态位置兜底（app.agent.location-static 已配）或内网文案；
 * 公网 IP → GeoLite2-City.mmdb 本地查询（零外呼），中文名优先缺失回退英文。
 *
 * 生命周期：DatabaseReader 惰性打开（缺库不阻断服务启动，调用期返回确定性文案）、
 * 全进程单例持有（mmap 一次，线程安全）、@PreDestroy 随进程关闭。
 *
 * 隐私边界：返回文案只含城市级位置，不向模型回显 IP 本身；
 * 解析前做 IP 字面量预检（首字符数字或含冒号），防伪造头携带主机名触发 DNS 查询。
 *
 * GeoLite2 数据库许可：CC BY 4.0（署名义务见 README）。
 *
 * @author tianbaoxing
 * @date 2026-10-18
 */
@Service
public class GeoLocationService {

    /** RuntimeContext 载荷键：AgentChatService 注入，GetLocationTool 读取（对齐 CTX_ROLE_KEY 模式） */
    public static final String CTX_CLIENT_IP_KEY = "clientIp";

    private static final Logger log = LoggerFactory.getLogger(GeoLocationService.class);

    /** mmdb 本地化名回退链：中文名优先，缺失回退英文（NamedRecord.name() 按此顺序取） */
    private static final List<String> LOCALES = List.of("zh-CN", "en");

    private final Path databasePath;
    private final String staticLocation;

    /** 惰性初始化的只读 DatabaseReader（官方实现线程安全），全进程打开一次 */
    private volatile DatabaseReader reader;

    public GeoLocationService(
            @Value("${app.agent.location-db:geolite2/GeoLite2-City.mmdb}") String databasePath,
            @Value("${app.agent.location-static:}") String staticLocation) {
        this.databasePath = Path.of(databasePath);
        this.staticLocation = staticLocation == null ? "" : staticLocation.strip();
    }

    /**
     * 解析客户端 IP → 城市级位置文案（确定性中文，各种异常路径均不抛出、不阻断对话）。
     * - 内网/回环：无法定位到城市（app.agent.location-static 已配则兜底返回）
     * - 数据库未部署：[工具错误] 前缀 + 部署指引（暴露部署问题，不吞掉）
     * - 公网 IP 不在库：未查询到（location-static 已配则兜底返回）
     */
    public String resolve(String clientIp) {
        if (clientIp == null || clientIp.isBlank()) {
            return "未能获取当前访问来源，无法定位当前位置";
        }
        String ip = clientIp.strip();
        if (!isIpLiteral(ip)) {
            return "访问来源格式异常，无法定位";
        }
        final InetAddress address;
        try {
            address = InetAddress.getByName(ip);
        } catch (UnknownHostException e) {
            return "访问来源格式异常，无法定位";
        }
        if (isPrivateAddress(address)) {
            return staticFallback("当前为内网访问，无法定位到城市");
        }
        DatabaseReader r = readerOrNull();
        if (r == null) {
            return "[工具错误] 定位数据库未部署：请下载 GeoLite2-City.mmdb 放至 "
                    + databasePath + "（获取渠道见 README）";
        }
        try {
            CityResponse response = r.tryCity(address).orElse(null);
            if (response == null) {
                return staticFallback("未查询到该访问来源的位置信息");
            }
            return format(response);
        } catch (Exception e) {
            log.warn("GeoLite2 查询异常：{}", e.getMessage());
            return "[工具错误] 定位查询执行失败：" + e.getMessage();
        }
    }

    // ---------------- 内部 ----------------

    /** IP 字面量预检（IPv4 以数字开头，IPv6 含冒号）；拒绝主机名，防 DNS 触发 */
    private static boolean isIpLiteral(String ip) {
        return !ip.isEmpty()
                && (Character.isDigit(ip.charAt(0)) || ip.indexOf(':') >= 0);
    }

    /** 内网/回环/链路本地/未指定地址统一按不可定位处理（JDK 判定，零手写 CIDR） */
    private static boolean isPrivateAddress(InetAddress address) {
        return address.isLoopbackAddress() || address.isSiteLocalAddress()
                || address.isLinkLocalAddress() || address.isAnyLocalAddress();
    }

    /** location-static 已配则返回其文本，否则返回原语义文案 */
    private String staticFallback(String message) {
        return staticLocation.isEmpty() ? message : staticLocation;
    }

    /**
     * 双检锁惰性打开 Reader；缺库/打开失败返回 null（部署态而非错误，
     * 文件可读性检查在锁外，纳秒级，允许部署后免重启直接生效）。
     */
    private DatabaseReader readerOrNull() {
        DatabaseReader r = reader;
        if (r != null) {
            return r;
        }
        if (!Files.isReadable(databasePath)) {
            return null;
        }
        synchronized (this) {
            if (reader == null) {
                try {
                    reader = new DatabaseReader.Builder(databasePath.toFile())
                            .locales(LOCALES)
                            .build();
                    log.info("GeoLite2 数据库已加载：{}", databasePath);
                } catch (IOException e) {
                    log.warn("GeoLite2 数据库打开失败（{}）：{}", databasePath, e.getMessage());
                    return null;
                }
            }
            return reader;
        }
    }

    /** 组装城市级文案：国家/省/市去重保序拼接，坐标/时区缺失则省略行 */
    private static String format(CityResponse response) {
        Set<String> parts = new LinkedHashSet<>();
        if (response.getCountry().getName() != null) {
            parts.add(response.getCountry().getName());
        }
        if (response.getMostSpecificSubdivision().getName() != null) {
            parts.add(response.getMostSpecificSubdivision().getName());
        }
        if (response.getCity().getName() != null) {
            parts.add(response.getCity().getName());
        }
        StringBuilder sb = new StringBuilder("当前位置：")
                .append(parts.isEmpty() ? "未知地区" : String.join(" ", parts))
                .append("\n");
        Location location = response.getLocation();
        if (location.getLatitude() != null && location.getLongitude() != null) {
            sb.append("约略坐标：").append(trimCoord(location.getLatitude()))
                    .append(", ").append(trimCoord(location.getLongitude())).append("\n");
        }
        if (location.getTimeZone() != null) {
            sb.append("时区：").append(location.getTimeZone()).append("\n");
        }
        sb.append("定位方式：客户端 IP 地理定位（城市级精度，部分城市可能有偏差）");
        return sb.toString();
    }

    /** 坐标保留两位小数（城市级精度下更多位数无意义） */
    private static String trimCoord(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    @PreDestroy
    public void shutdown() {
        if (reader != null) {
            try {
                reader.close();
            } catch (IOException e) {
                log.warn("GeoLite2 Reader 关闭异常：{}", e.getMessage());
            }
        }
    }
}
