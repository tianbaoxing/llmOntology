package com.conca.ontology.agent.tool;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

/**
 * @author tianbaoxing
 * @date 2026-09-13
 */
public class ChartPluginTool {

    private static final Logger log = LoggerFactory.getLogger(ChartPluginTool.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> ALLOWED_TYPES = Set.of("line", "bar", "pie");
    private static final String ERR_PREFIX = "[build_chart 错误] ";

    @Tool(name = "build_chart", readOnly = true, concurrencySafe = true,
            description = "【必须调用时机】当你准备输出一张 ≥3 行的数值型 Markdown 表格，或用户问对比/趋势/占比/排行/数量类统计时，先调用本工具把原始数据转前端可渲染的标准 JSON。"
                    + "【参数】type ∈ {line(趋势), bar(对比), pie(占比)}；title 简短中文；xAxis 类别名数组；"
                    + "series 格式 [{name:系列名, data:[数值数组]}]（各 data.length 必须等于 xAxis.length 且每项是数字；pie 只允许 1 个 series）；yAxisName 可选单位名。"
                    + "【返回使用】成功返回一行 JSON + 转写指令：把那行 JSON **原样**包入 ```chart 代码围栏输出（独立一行，不得增删改字段，不得换行美化）。"
                    + "图表下方仍可照常输出 Markdown 明细表备查，两者并存。"
                    + "【错误处理】若返回 [build_chart 错误] 开头，说明参数不合规，按提示修正后立即重调，不得带着错误继续输出。")
    public String buildChart(
            RuntimeContext ctx,
            @ToolParam(name = "type", description = "图表类型，白名单：line（折线）、bar（柱状）、pie（饼图）") String type,
            @ToolParam(name = "title", description = "图表标题，简短中文，如 \"各朝代青花瓷藏品数量\"") String title,
            @ToolParam(name = "xAxis",
                    description = "类别轴数组（pie 时作为每块的名称），例：[\"元\",\"明洪武\",\"清康熙\"]") List<String> xAxis,
            @ToolParam(name = "series",
                    description = "数值系列数组，每项为 {name:系列名, data:数值数组}；"
                            + "pie 时只传 1 个 series，data[i] 对应 xAxis[i] 的数值") List<Map<String, Object>> series,
            @ToolParam(name = "yAxisName", required = false,
                    description = "Y 轴单位名称，可选，如 \"件\"、\"万元\"；pie 图忽略") String yAxisName) {

        try {
            String err = validate(type, xAxis, series);
            if (err != null) {
                return ERR_PREFIX + err;
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("type", type.trim());
            out.put("title", title == null ? "" : title.trim());
            out.put("xAxis", xAxis);
            out.put("series", series);
            if (yAxisName != null && !yAxisName.isBlank() && !"pie".equals(type)) {
                out.put("yAxisName", yAxisName.trim());
            }

            String json = MAPPER.writeValueAsString(out);
            return json + "\n\n——转写指令——将上方那一行 JSON 原样放入 ```chart 围栏输出到回答中，"
                    + "不得增删改任何字段、不得换行、不得添加任何注释或说明。围栏块可放在回答任意位置。";
        } catch (Exception e) {
            log.error("build_chart failed", e);
            return ERR_PREFIX + "工具内部异常：" + e.getMessage();
        }
    }

    private static String validate(String type, List<String> xAxis, List<Map<String, Object>> series) {
        if (type == null || !ALLOWED_TYPES.contains(type.trim())) {
            return "type 必须是 line/bar/pie，收到 \"" + (type == null ? "null" : type) + "\"";
        }
        String t = type.trim();

        if (xAxis == null || xAxis.isEmpty()) {
            return "xAxis 与 series 不能为空";
        }
        if (series == null || series.isEmpty()) {
            return "xAxis 与 series 不能为空";
        }

        int xlen = xAxis.size();

        if ("pie".equals(t) && series.size() > 1) {
            return "pie 图仅支持 1 个 series，收到 " + series.size() + " 个";
        }

        for (int si = 0; si < series.size(); si++) {
            Map<String, Object> s = series.get(si);
            String sname = s.get("name") == null ? ("第" + (si + 1) + "个系列") : String.valueOf(s.get("name"));
            Object dataObj = s.get("data");
            if (!(dataObj instanceof List)) {
                return "系列「" + sname + "」缺少 data 数组或格式错误";
            }
            List<?> data = (List<?>) dataObj;
            if (data.size() != xlen) {
                return "xAxis 有 " + xlen + " 项，系列「" + sname + "」data 有 " + data.size() + " 项，长度必须一致";
            }
            for (int di = 0; di < data.size(); di++) {
                Object v = data.get(di);
                if (!(v instanceof Number)) {
                    return "系列「" + sname + "」data 第 " + (di + 1) + " 项 \"" + (v == null ? "null" : v) + "\" 不是数值";
                }
            }
        }
        return null;
    }
}
