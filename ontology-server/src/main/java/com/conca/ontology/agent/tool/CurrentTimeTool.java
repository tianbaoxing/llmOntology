package com.conca.ontology.agent.tool;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;

/**
 * 当前时间工具：为 LLM 提供服务端时钟锚点，抑制"凭训练截止日期猜测今天"的幻觉。
 * 无状态、无参数、无 IO：一次调用返回日期时间/星期/时区/Unix 时间戳。
 *
 * @author tianbaoxing
 * @date 2026-10-16
 */
public class CurrentTimeTool {

    private static final Logger log = LoggerFactory.getLogger(CurrentTimeTool.class);
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Tool(name = "current_time", readOnly = true, concurrencySafe = true,
            description = "获取服务端的当前日期和时间（含星期几与时区），无参数，调用即返回。"
                    + "【必须调用时机】用户问题涉及\"今天/现在/今年/星期几/几点/日期/时间\"等时效性表述，"
                    + "或回答需要以当前日期为锚点（如计算年龄、距今年数、近期/最新）时，必须先调用本工具，"
                    + "禁止凭训练记忆猜测或自行推算当前日期。"
                    + "【返回示例】当前日期时间：2026-10-16 14:30:25（星期五）；时区：Asia/Shanghai（UTC+08:00）；"
                    + "Unix 时间戳（毫秒）：1792132225000。"
                    + "与时限无关的问题不要调用本工具。")
    public String currentTime(RuntimeContext ctx) {
        try {
            ZonedDateTime now = ZonedDateTime.now();
            String weekday = now.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.CHINA);
            return "当前日期时间：" + DATE_TIME.format(now) + "（" + weekday + "）\n"
                    + "时区：" + now.getZone().getId() + "（UTC" + now.getOffset() + "）\n"
                    + "Unix 时间戳（毫秒）：" + now.toInstant().toEpochMilli();
        } catch (Exception e) {
            log.error("current_time failed", e);
            return "[工具错误] current_time 执行失败：" + e.getMessage();
        }
    }
}
