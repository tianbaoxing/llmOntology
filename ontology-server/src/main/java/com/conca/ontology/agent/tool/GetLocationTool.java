package com.conca.ontology.agent.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.conca.ontology.agent.service.GeoLocationService;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;

/**
 * 位置工具：为 LLM 提供当前访问者（用户）的位置锚点，抑制"凭训练记忆猜测用户所在地"的幻觉。
 * 与 CurrentTimeTool 同构：无参数、零注入面；IP 不由模型传入，而是 AgentChatService
 * 经 RuntimeContext（CTX_CLIENT_IP_KEY）随请求流注入，GeoLocationService 本地 mmdb 查询。
 *
 * @author tianbaoxing
 * @date 2026-10-18
 */
public class GetLocationTool {

    private static final Logger log = LoggerFactory.getLogger(GetLocationTool.class);

    private final GeoLocationService locationService;

    public GetLocationTool(GeoLocationService locationService) {
        this.locationService = locationService;
    }

    @Tool(name = "get_location", readOnly = true, concurrencySafe = true,
            description = "获取当前访问者（用户）的城市级地理位置，无参数，调用即返回。"
                    + "【必须调用时机】用户问题涉及\"本地/附近/周边/当地/在哪个城市\"等位置语境，"
                    + "或回答需要以用户位置为锚点（如天气、周边推荐、本地化内容）时，必须先调用本工具，"
                    + "禁止凭训练记忆或对话猜测用户位置。"
                    + "【返回示例】当前位置：中国 北京市 北京市；约略坐标：39.90, 116.41；"
                    + "时区：Asia/Shanghai；定位方式为客户端 IP 地理定位（城市级精度，部分城市可能有偏差）。"
                    + "与位置无关的问题不要调用本工具。")
    public String getLocation(RuntimeContext ctx) {
        try {
            String clientIp = ctx.get(GeoLocationService.CTX_CLIENT_IP_KEY, String.class);
            return locationService.resolve(clientIp);
        } catch (Exception e) {
            log.error("get_location failed", e);
            return "[工具错误] get_location 执行失败：" + e.getMessage();
        }
    }
}
