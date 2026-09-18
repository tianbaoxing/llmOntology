package com.conca.ontology.eval.graders.rule.text;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.conca.ontology.eval.Grader;
import com.conca.ontology.eval.model.GraderInput;
import com.conca.ontology.eval.model.GraderScore;

/**
 * 回答重复检测评测（规则，OpenJudge ResponseRepetitionGrader 的规则版）：
 * 回答内字符 4-gram 重复率——重复 4-gram（出现 >1 次的多重集占比）过高说明
 * 刷屏式复读。score = 1 - 重复率；回答过短（< 16 字符）无统计意义直接得 1 分。
 */
@Component
public class ResponseRepetitionGrader implements Grader {

    private static final int NGRAM = 4;
    /** 短回答阈值：n-gram 统计无统计意义 */
    private static final int MIN_LENGTH = 16;

    @Override
    public String name() {
        return "response_repetition";
    }

    @Override
    public String description() {
        return "回答重复检测（规则）：字符 4-gram 重复率过高判为刷屏复读，score = 1 - 重复率；短回答（<16 字符）得 1 分";
    }

    @Override
    public GraderScore evaluate(GraderInput input) {
        if (input.record().failed()) {
            return GraderScore.fail(name(), "agent 调用失败：" + input.record().error());
        }
        String answer = input.response() == null ? "" : input.response();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("answerLength", answer.length());
        meta.put("ngram", NGRAM);
        if (answer.length() < MIN_LENGTH) {
            return GraderScore.of(name(), 1.0, "回答过短（<" + MIN_LENGTH + " 字符），无重复风险", meta);
        }
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i + NGRAM <= answer.length(); i++) {
            counts.merge(answer.substring(i, i + NGRAM), 1, Integer::sum);
        }
        int total = 0;
        int repeated = 0;
        for (int c : counts.values()) {
            total += c;
            if (c > 1) {
                repeated += c - 1; // 每个多余出现计一次重复
            }
        }
        double ratio = total == 0 ? 0 : repeated * 1.0 / total;
        double score = 1.0 - ratio;
        meta.put("totalNgrams", total);
        meta.put("repeatedNgrams", repeated);
        meta.put("repetitionRate", TextSimilarityGrader.round4(ratio));
        String reason = repeated == 0
                ? "无重复 4-gram，行文无复读"
                : "检测到 " + repeated + " 个重复 4-gram（重复率 " + TextSimilarityGrader.round4(ratio) + "）";
        return GraderScore.of(name(), score, reason, meta);
    }
}
