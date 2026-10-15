package com.conca.ontology.eval.graders.rule.tool;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.conca.ontology.eval.Grader;
import com.conca.ontology.eval.model.GraderInput;
import com.conca.ontology.eval.model.GraderScore;

/**
 * 工具调用成功率 + 期望召回率 合成评测（二期规则补强）：
 * <ol>
 *   <li>原规则分：轨迹中工具返回含 [参数错误]/[服务错误]/[连接失败] 前缀
 *       或 state 含 ERROR/FAIL 的调用判失败，original = 成功数 / 总数。</li>
 *   <li>期望召回率（样本设了 expected_tools / expected_mcps 时启用）：
 *       按普通工具 / MCP 工具拆分期望集合，算加权召回率 recall。</li>
 *   <li>合成 score：有期望时 score = 0.5 × recall + 0.5 × original；
 *       无期望时保持原算法，完全兼容旧数据集。</li>
 *   <li>未调用工具（含纯判分样本）得 1 分并注明跳过。</li>
 * </ol>
 *
 * @author tianbaoxing
 * @date 2026-09-16
 */
@Component
public class ToolCallSuccessGrader implements Grader {

    private static final String[] ERROR_PREFIXES = {"[参数错误]", "[服务错误]", "[连接失败]"};
    /** 轻量判定技能实际使用：load_skill* 成功输出 */
    private static final Pattern LOAD_OK = Pattern.compile("Successfully loaded skill:\\s*(\\S+)");

    @Override
    public String name() {
        return "tool_call_success";
    }

    @Override
    public String description() {
        return "工具调用成功率（规则，合成期望召回率 0.5 + 成功率 0.5）："
                + "样本带期望集合时同时考核「期望工具/MCP 用到了多少」与「调用是否报错」；"
                + "无期望集合时与旧算法一致；未调用工具跳过得 1 分";
    }

    @Override
    public GraderScore evaluate(GraderInput input) {
        if (input.record().failed()) {
            return GraderScore.fail(name(), "agent 调用失败：" + input.record().error());
        }
        List<Map<String, Object>> toolCalls = input.record().toolCalls();
        Map<String, Object> meta = new LinkedHashMap<>();
        if (toolCalls == null || toolCalls.isEmpty()) {
            appendExpectedMeta(meta, input);
            return GraderScore.of(name(), 1.0, "未调用工具，无轨迹可评", meta);
        }
        // ---- 1. 原成功率算法 ----
        int failed = 0;
        List<String> failedDesc = new ArrayList<>();
        for (Map<String, Object> t : toolCalls) {
            String result = String.valueOf(t.getOrDefault("result", ""));
            String state = String.valueOf(t.getOrDefault("state", ""));
            boolean bad = false;
            for (String p : ERROR_PREFIXES) {
                if (result.startsWith(p)) {
                    bad = true;
                    break;
                }
            }
            if (!bad && (state.toUpperCase().contains("ERROR") || state.toUpperCase().contains("FAIL"))) {
                bad = true;
            }
            if (bad) {
                failed++;
                failedDesc.add(t.getOrDefault("name", "?") + "(" + state + ")");
            }
        }
        double originalSuccess = (toolCalls.size() - failed) * 1.0 / toolCalls.size();
        meta.put("total", toolCalls.size());
        meta.put("failed", failed);
        meta.put("originalSuccess", originalSuccess);
        if (!failedDesc.isEmpty()) {
            meta.put("failedCalls", failedDesc);
        }

        List<String> expTools = input.expectedTools();
        List<String> expMcps = input.expectedMcps();
        List<String> expSkills = input.expectedSkills();
        appendExpectedMeta(meta, expSkills, expTools, expMcps);

        // ---- 2. 无期望集合 → 完全走旧逻辑 ----
        if (expTools.isEmpty() && expMcps.isEmpty()) {
            String reason = failed == 0
                    ? "全部 " + toolCalls.size() + " 次工具调用均成功"
                    : "工具调用成功 " + (toolCalls.size() - failed) + "/" + toolCalls.size()
                            + "，失败：" + String.join("、", failedDesc);
            return GraderScore.of(name(), originalSuccess, reason, meta);
        }

        // ---- 3. 拆分实际调用：普通工具 / MCP 工具 两组 ----
        Set<String> normTools = new HashSet<>();
        Set<String> normMcps = new HashSet<>();
        Set<String> usedSkills = new HashSet<>();
        for (Map<String, Object> t : toolCalls) {
            String nm = norm(String.valueOf(t.getOrDefault("name", "")));
            if (nm.isEmpty()) continue;
            if (isMcpCall(t, nm)) {
                normMcps.add(nm);
            } else {
                normTools.add(nm);
            }
            Matcher m = LOAD_OK.matcher(String.valueOf(t.getOrDefault("result", "")));
            if (m.find()) usedSkills.add(norm(m.group(1)));
            Object output = t.get("output");
            if (output != null) {
                m = LOAD_OK.matcher(String.valueOf(output));
                if (m.find()) usedSkills.add(norm(m.group(1)));
            }
        }

        // ---- 4. 分别算召回率，按期望组数归一 ----
        double recallSum = 0.0;
        int recallGroups = 0;
        if (!expTools.isEmpty()) {
            RecallResult r = recall(normalizeSet(expTools), normTools);
            recallSum += r.value;
            recallGroups++;
            meta.put("recall_tools", r.value);
            if (!r.missed.isEmpty()) meta.put("missed_tools", new ArrayList<>(r.missed));
            if (!r.extra.isEmpty()) meta.put("extra_tools", new ArrayList<>(r.extra));
        }
        if (!expMcps.isEmpty()) {
            RecallResult r = recall(normalizeSet(expMcps), normMcps);
            recallSum += r.value;
            recallGroups++;
            meta.put("recall_mcps", r.value);
            if (!r.missed.isEmpty()) meta.put("missed_mcps", new ArrayList<>(r.missed));
            if (!r.extra.isEmpty()) meta.put("extra_mcps", new ArrayList<>(r.extra));
        }
        if (!expSkills.isEmpty()) {
            // skills 组仅供 metadata 参考，不参与合成 score（按确认的 Q2-A 方案）
            Set<String> normExpSkills = normalizeSet(expSkills);
            Set<String> missed = minus(normExpSkills, usedSkills);
            Set<String> extra = minus(usedSkills, normExpSkills);
            double skillsRecall = normExpSkills.isEmpty() ? 1.0
                    : (normExpSkills.size() - missed.size()) * 1.0 / normExpSkills.size();
            meta.put("used_skills", new ArrayList<>(usedSkills));
            meta.put("skills_recall_ref", skillsRecall);
            if (!missed.isEmpty()) meta.put("missed_skills_ref", new ArrayList<>(missed));
            if (!extra.isEmpty()) meta.put("extra_skills_ref", new ArrayList<>(extra));
            meta.put("skills_unavailable", usedSkills.isEmpty());
        }

        double recallAvg = recallGroups == 0 ? 1.0 : recallSum / recallGroups;
        meta.put("recall_average", recallAvg);
        double score = 0.5 * recallAvg + 0.5 * originalSuccess;

        StringBuilder reason = new StringBuilder();
        if (failed == 0) {
            reason.append("全部 ").append(toolCalls.size()).append(" 次工具调用均成功；");
        } else {
            reason.append("工具调用成功 ").append(toolCalls.size() - failed).append("/").append(toolCalls.size());
            if (!failedDesc.isEmpty()) reason.append("，失败：").append(String.join("、", failedDesc));
            reason.append("；");
        }
        reason.append("期望召回率平均 ").append(fmt3(recallAvg)).append("；合成 0.5×召回率 + 0.5×成功率 = ").append(fmt3(score));
        return GraderScore.of(name(), score, reason.toString(), meta);
    }

    // ------------------------------------------------------------

    private static boolean isMcpCall(Map<String, Object> t, String nm) {
        Object isMcp = t.get("isMcp");
        if (Boolean.TRUE.equals(isMcp) || "true".equalsIgnoreCase(String.valueOf(isMcp))) return true;
        Object type = t.get("type");
        if ("mcp".equalsIgnoreCase(String.valueOf(type))) return true;
        Object source = t.get("tool_source");
        if (source == null) source = t.get("source");
        if ("mcp".equalsIgnoreCase(String.valueOf(source))) return true;
        // 回退命名约定：name 含 "__" 视为 MCP 工具（server__tool）
        return nm.contains("__");
    }

    private static class RecallResult {
        final double value;
        final Set<String> missed;
        final Set<String> extra;
        RecallResult(double v, Set<String> missed, Set<String> extra) {
            this.value = v; this.missed = missed; this.extra = extra;
        }
    }

    private static RecallResult recall(Set<String> expected, Set<String> actual) {
        Set<String> missed = minus(expected, actual);
        Set<String> extra = minus(actual, expected);
        double v = expected.isEmpty() ? 1.0 : (expected.size() - missed.size()) * 1.0 / expected.size();
        return new RecallResult(v, missed, extra);
    }

    private static Set<String> normalizeSet(List<String> list) {
        Set<String> s = new HashSet<>();
        for (String e : list) {
            String n = norm(e);
            if (!n.isEmpty()) s.add(n);
        }
        return s;
    }

    private static Set<String> minus(Set<String> a, Set<String> b) {
        Set<String> out = new HashSet<>(a);
        out.removeAll(b);
        return out;
    }

    private static String norm(String s) {
        if (s == null) return "";
        String r = s.trim().toLowerCase();
        return "null".equals(r) ? "" : r;
    }

    private static String fmt3(double v) {
        return String.format(java.util.Locale.ROOT, "%.3f", v);
    }

    private static void appendExpectedMeta(Map<String, Object> meta, GraderInput input) {
        appendExpectedMeta(meta, input.expectedSkills(), input.expectedTools(), input.expectedMcps());
    }

    private static void appendExpectedMeta(Map<String, Object> meta,
                                           List<String> skills, List<String> tools, List<String> mcps) {
        if (!skills.isEmpty()) meta.put("expected_skills", skills);
        if (!tools.isEmpty()) meta.put("expected_tools", tools);
        if (!mcps.isEmpty()) meta.put("expected_mcps", mcps);
    }
}
