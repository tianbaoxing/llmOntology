package com.conca.ontology.eval.graders;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.conca.ontology.eval.AgentRecord;
import com.conca.ontology.eval.EvalSample;
import com.conca.ontology.eval.Grader;
import com.conca.ontology.eval.GraderScore;

/**
 * 整串等值评测：expected = {type:"exact", value:"..."}。
 * 回答去首尾空白后与期望值全等得 1.0，否则 0.0（严苛模式，适合答案高度受限的问题）。
 */
@Component
public class StringExactGrader implements Grader {

    @Override
    public String name() {
        return "string_exact";
    }

    @Override
    public String description() {
        return "整串等值比对：回答去首尾空白后与 expected.value 全等得 1 分，适合答案高度受限（如纯数字）的问题";
    }

    @Override
    public GraderScore evaluate(EvalSample sample, AgentRecord record) {
        Object value = sample.expected() == null ? null : sample.expected().get("value");
        if (value == null || String.valueOf(value).isBlank()) {
            return GraderScore.fail(name(), "[参数错误] expected.value 缺失，无法进行整串等值判分");
        }
        if (record.failed()) {
            return GraderScore.fail(name(), "agent 调用失败：" + record.error());
        }
        String expected = String.valueOf(value).trim();
        String actual = record.answer() == null ? "" : record.answer().trim();
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
