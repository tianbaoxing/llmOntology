package com.conca.ontology.eval.graders.rule.match;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.conca.ontology.eval.Grader;
import com.conca.ontology.eval.model.GraderInput;
import com.conca.ontology.eval.model.GraderScore;

/**
 * 关键词包含评测（对齐 OpenJudge StringMatchGrader 的 contains_all 算法，按比例得分）：
 * reference_response = ["关键词",...]（也兼容单个字符串/数字）。
 * score = 命中关键词数 / 总数（缺一按比例扣分），metadata.missing 列出未命中的关键词。
 *
 * @author tianbaoxing
 * @date 2026-09-16
 */
@Component
public class StringContainsGrader implements Grader {

    @Override
    public String name() {
        return "string_contains";
    }

    @Override
    public String description() {
        return "关键词包含比对：回答须包含 reference_response 中的全部关键词，按命中比例得分（如 3 个关键词命中 2 个得 0.67）";
    }

    @Override
    public GraderScore evaluate(GraderInput input) {
        List<String> keywords = toStringList(input.referenceResponse());
        if (keywords.isEmpty()) {
            return GraderScore.fail(name(), "[参数错误] reference_response 缺失或为空，无法进行关键词判分");
        }
        if (input.record().failed()) {
            return GraderScore.fail(name(), "agent 调用失败：" + input.record().error());
        }
        String answer = input.response() == null ? "" : input.response();
        if (answer.isBlank()) {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("keywords", keywords);
            return GraderScore.of(name(), 0.0, "回答为空，所有关键词均未命中", meta);
        }
        List<String> missing = new ArrayList<>();
        for (String k : keywords) {
            if (!answer.contains(k)) {
                missing.add(k);
            }
        }
        double score = (keywords.size() - missing.size()) * 1.0 / keywords.size();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("keywords", keywords);
        meta.put("missing", missing);
        String reason = missing.isEmpty() ? "全部 " + keywords.size() + " 个关键词均已命中"
                : "关键词命中 " + (keywords.size() - missing.size()) + "/" + keywords.size()
                        + "，未命中：" + String.join("、", missing);
        return GraderScore.of(name(), score, reason, meta);
    }

    /** reference_response 兼容单值 / 数组 */
    static List<String> toStringList(Object value) {
        List<String> list = new ArrayList<>();
        if (value instanceof List<?> l) {
            for (Object o : l) {
                if (o != null && !String.valueOf(o).isBlank()) {
                    list.add(String.valueOf(o));
                }
            }
        } else if (value != null && !String.valueOf(value).isBlank()) {
            list.add(String.valueOf(value));
        }
        return list;
    }
}
