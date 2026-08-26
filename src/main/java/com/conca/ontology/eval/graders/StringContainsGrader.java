package com.conca.ontology.eval.graders;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.conca.ontology.eval.AgentRecord;
import com.conca.ontology.eval.EvalSample;
import com.conca.ontology.eval.Grader;
import com.conca.ontology.eval.GraderScore;

/**
 * 关键词包含评测：expected = {type:"contains", value:["关键词",...]}（也兼容单个字符串）。
 * score = 命中关键词数 / 总数（缺一按比例扣分），metadata.missing 列出未命中的关键词。
 */
@Component
public class StringContainsGrader implements Grader {

    @Override
    public String name() {
        return "string_contains";
    }

    @Override
    public String description() {
        return "关键词包含比对：回答须包含 expected.value 中的全部关键词，按命中比例得分（如 3 个关键词命中 2 个得 0.67）";
    }

    @Override
    public GraderScore evaluate(EvalSample sample, AgentRecord record) {
        Object value = sample.expected() == null ? null : sample.expected().get("value");
        List<String> keywords = toStringList(value);
        if (keywords.isEmpty()) {
            return GraderScore.fail(name(), "[参数错误] expected.value 缺失或为空，无法进行关键词判分");
        }
        if (record.failed()) {
            return GraderScore.fail(name(), "agent 调用失败：" + record.error());
        }
        String answer = record.answer() == null ? "" : record.answer();
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

    /** value 兼容单字符串 / 字符串数组 */
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
