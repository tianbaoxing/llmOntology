package com.conca.ontology.eval.graders.rule.tool;

import com.conca.ontology.eval.graders.rule.text.TextSimilarityGrader;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.conca.ontology.eval.Grader;
import com.conca.ontology.eval.model.GraderInput;
import com.conca.ontology.eval.model.GraderScore;

/**
 * 死循环检测评测（二期规则补强，OpenJudge ActionLoopDetectionGrader 的规则版）：
 * 连续调用相同工具且参数相同判为循环步，score = 1 - 循环步数 / (总调用数 - 1)；
 * 调用数 ≤ 1（含未调用工具的纯判分样本）视为无循环风险得 1 分。
 *
 * @author tianbaoxing
 * @date 2026-09-16
 */
@Component
public class ActionLoopGrader implements Grader {

    @Override
    public String name() {
        return "action_loop";
    }

    @Override
    public String description() {
        return "死循环检测（规则）：连续相同工具+相同参数判为循环步，按循环占比扣分；调用数≤1 得 1 分";
    }

    @Override
    public GraderScore evaluate(GraderInput input) {
        if (input.record().failed()) {
            return GraderScore.fail(name(), "agent 调用失败：" + input.record().error());
        }
        List<Map<String, Object>> toolCalls = input.record().toolCalls();
        Map<String, Object> meta = new LinkedHashMap<>();
        if (toolCalls == null || toolCalls.size() <= 1) {
            meta.put("total", toolCalls == null ? 0 : toolCalls.size());
            appendExpectedMeta(meta, input);
            return GraderScore.of(name(), 1.0, "调用次数不足，无循环风险", meta);
        }
        int loops = 0;
        for (int i = 1; i < toolCalls.size(); i++) {
            Map<String, Object> cur = toolCalls.get(i);
            Map<String, Object> prev = toolCalls.get(i - 1);
            if (String.valueOf(cur.get("name")).equals(String.valueOf(prev.get("name")))
                    && String.valueOf(cur.get("args")).equals(String.valueOf(prev.get("args")))) {
                loops++;
            }
        }
        double score = 1.0 - loops * 1.0 / (toolCalls.size() - 1);
        meta.put("total", toolCalls.size());
        meta.put("loops", loops);
        appendExpectedMeta(meta, input);
        String reason = loops == 0
                ? toolCalls.size() + " 次调用无连续重复"
                : "检测到 " + loops + " 个循环步（连续同工具同参数）";
        return GraderScore.of(name(), score, reason, meta);
    }

    private static void appendExpectedMeta(Map<String, Object> meta, GraderInput input) {
        List<String> sk = input.expectedSkills();
        List<String> tl = input.expectedTools();
        List<String> mp = input.expectedMcps();
        if (!sk.isEmpty()) meta.put("expected_skills", sk);
        if (!tl.isEmpty()) meta.put("expected_tools", tl);
        if (!mp.isEmpty()) meta.put("expected_mcps", mp);
    }
}
