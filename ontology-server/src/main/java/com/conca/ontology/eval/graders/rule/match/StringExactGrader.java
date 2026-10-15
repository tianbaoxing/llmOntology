package com.conca.ontology.eval.graders.rule.match;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.conca.ontology.eval.Grader;
import com.conca.ontology.eval.model.GraderInput;
import com.conca.ontology.eval.model.GraderScore;

/**
 * 整串等值评测（对齐 OpenJudge StringMatchGrader 的 exact_match 算法）：
 * reference_response = "..."（字符串/数字均可）。
 * 回答去首尾空白后与参考答案全等得 1.0，否则 0.0（严苛模式，适合答案高度受限的问题）。
 *
 * @author tianbaoxing
 * @date 2026-09-16
 */
@Component
public class StringExactGrader implements Grader {

    @Override
    public String name() {
        return "string_exact";
    }

    @Override
    public String description() {
        return "整串等值比对：回答去首尾空白后与 reference_response 全等得 1 分，适合答案高度受限（如纯数字）的问题";
    }

    @Override
    public GraderScore evaluate(GraderInput input) {
        Object value = input.referenceResponse();
        if (value == null || String.valueOf(value).isBlank()) {
            return GraderScore.fail(name(), "[参数错误] reference_response 缺失，无法进行整串等值判分");
        }
        if (input.record().failed()) {
            return GraderScore.fail(name(), "agent 调用失败：" + input.record().error());
        }
        String expected = String.valueOf(value).trim();
        String actual = input.response() == null ? "" : input.response().trim();
        boolean matched = expected.equals(actual);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("expected", expected);
        meta.put("actual", truncate(actual, 200));
        return GraderScore.of(name(), matched ? 1.0 : 0.0,
                matched ? "回答与期望值完全一致"
                        : "回答与期望值不一致（exact 为严苛模式，多一个字即判 0，建议多数场景用 string_contains）",
                meta);
    }

    static String truncate(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "…（共" + s.length() + "字符）";
    }
}
