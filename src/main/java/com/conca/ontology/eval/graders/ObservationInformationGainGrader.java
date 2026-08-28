package com.conca.ontology.eval.graders;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.conca.ontology.eval.Grader;
import com.conca.ontology.eval.GraderInput;
import com.conca.ontology.eval.GraderScore;

/**
 * 观察信息增益评测（规则，OpenJudge ObservationInformationGainGrader 的规则版）：
 * 检测工具观察序列的重复查询——每次工具返回与之前任一次返回的字符 rouge1 相似度
 * ≥ 阈值即判为「无新信息」步。score = 1 - 无新信息步数 / 总调用数；
 * 调用数 ≤ 1（含未调用工具的纯判分样本）视为无重复风险得 1 分。
 */
@Component
public class ObservationInformationGainGrader implements Grader {

    /** 与先前观察的相似度阈值：达到即判该步无新信息（重复查询） */
    private static final double REPEAT_THRESHOLD = 0.85;
    /** 参与相似度计算的单条结果最大字符数（防超长 DP，与轨迹截断上限一致） */
    private static final int RESULT_CLIP = 500;

    @Override
    public String name() {
        return "observation_information_gain";
    }

    @Override
    public String description() {
        return "观察信息增益（规则）：工具返回与先前相似度≥0.85 判为重复查询，按无新信息占比扣分；调用数≤1 得 1 分";
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
            return GraderScore.of(name(), 1.0, "调用次数不足，无重复观察风险", meta);
        }
        List<String> results = new ArrayList<>();
        int redundant = 0;
        List<Integer> redundantSteps = new ArrayList<>();
        for (int i = 0; i < toolCalls.size(); i++) {
            String result = clip(String.valueOf(toolCalls.get(i).getOrDefault("result", "")));
            double maxSim = 0.0;
            for (String prev : results) {
                maxSim = Math.max(maxSim, TextSimilarityGrader.ngramF1(result, prev, 1));
            }
            if (i > 0 && maxSim >= REPEAT_THRESHOLD) {
                redundant++;
                redundantSteps.add(i + 1);
            }
            results.add(result);
        }
        double score = 1.0 - redundant * 1.0 / toolCalls.size();
        meta.put("total", toolCalls.size());
        meta.put("redundant", redundant);
        meta.put("redundantSteps", redundantSteps);
        meta.put("threshold", REPEAT_THRESHOLD);
        String reason = redundant == 0
                ? toolCalls.size() + " 次调用每次均有新信息（与先前观察相似度均 < " + REPEAT_THRESHOLD + "）"
                : "第 " + redundantSteps + " 步为重复查询（返回与先前观察相似度 ≥ " + REPEAT_THRESHOLD + "）";
        return GraderScore.of(name(), score, reason, meta);
    }

    private static String clip(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        return s.length() <= RESULT_CLIP ? s : s.substring(0, RESULT_CLIP);
    }
}
