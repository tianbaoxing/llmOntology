package com.conca.ontology.eval.graders.rule.tool;

import com.conca.ontology.eval.graders.rule.text.TextSimilarityGrader;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.conca.ontology.eval.Grader;
import com.conca.ontology.eval.GraderInput;
import com.conca.ontology.eval.GraderScore;

/**
 * 工具调用成功率评测（二期规则补强，OpenJudge ToolCallSuccessGrader 的规则版）：
 * 轨迹中工具返回含 [参数错误]/[服务错误]/[连接失败] 前缀（对齐项目错误信息分层约定）
 * 或 state 含 ERROR/FAIL 的调用判失败，score = 成功数 / 总数。
 * 未调用工具（含纯判分样本）无轨迹可评，得 1 分并注明跳过。
 */
@Component
public class ToolCallSuccessGrader implements Grader {

    private static final String[] ERROR_PREFIXES = {"[参数错误]", "[服务错误]", "[连接失败]"};

    @Override
    public String name() {
        return "tool_call_success";
    }

    @Override
    public String description() {
        return "工具调用成功率（规则）：轨迹中工具返回无错误前缀且 state 正常的比例；未调用工具跳过得 1 分";
    }

    @Override
    public GraderScore evaluate(GraderInput input) {
        if (input.record().failed()) {
            return GraderScore.fail(name(), "agent 调用失败：" + input.record().error());
        }
        List<Map<String, Object>> toolCalls = input.record().toolCalls();
        if (toolCalls == null || toolCalls.isEmpty()) {
            return GraderScore.of(name(), 1.0, "未调用工具，无轨迹可评", Map.of());
        }
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
        double score = (toolCalls.size() - failed) * 1.0 / toolCalls.size();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("total", toolCalls.size());
        meta.put("failed", failed);
        if (!failedDesc.isEmpty()) {
            meta.put("failedCalls", failedDesc);
        }
        String reason = failed == 0
                ? "全部 " + toolCalls.size() + " 次工具调用均成功"
                : "工具调用成功 " + (toolCalls.size() - failed) + "/" + toolCalls.size()
                        + "，失败：" + String.join("、", failedDesc);
        return GraderScore.of(name(), score, reason, meta);
    }
}
