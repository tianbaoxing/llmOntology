package com.conca.ontology.eval.graders;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.conca.ontology.eval.AgentRecord;
import com.conca.ontology.eval.EvalSample;
import com.conca.ontology.eval.Grader;
import com.conca.ontology.eval.GraderScore;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * JSON 语法校验评测：expected = {type:"json"}。
 * 回答剥离 markdown 代码围栏后须可解析为合法 JSON；样本未要求 JSON 格式时跳过（记 1 分并注明）。
 */
@Component
public class FormatJsonGrader implements Grader {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String name() {
        return "format_json";
    }

    @Override
    public String description() {
        return "JSON 语法校验：回答（剥离 markdown 围栏后）须为合法 JSON，用于要求结构化输出的问题";
    }

    @Override
    public GraderScore evaluate(EvalSample sample, AgentRecord record) {
        boolean required = sample.expected() != null && "json".equals(sample.expected().get("type"));
        if (!required) {
            return GraderScore.of(name(), 1.0, "样本未要求 JSON 格式，跳过校验", Map.of());
        }
        if (record.failed()) {
            return GraderScore.fail(name(), "agent 调用失败：" + record.error());
        }
        String answer = stripFence(record.answer() == null ? "" : record.answer().trim());
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("normalized", StringExactGrader.truncate(answer, 200));
        try {
            MAPPER.readTree(answer);
            return GraderScore.of(name(), 1.0, "回答是合法 JSON", meta);
        } catch (Exception e) {
            return GraderScore.of(name(), 0.0, "回答不是合法 JSON：" + e.getMessage(), meta);
        }
    }

    /** 剥离 ```json ... ``` / ``` ... ``` 围栏 */
    static String stripFence(String s) {
        if (s.startsWith("```")) {
            int firstNewline = s.indexOf('\n');
            if (firstNewline > 0) {
                s = s.substring(firstNewline + 1);
            }
            int fence = s.lastIndexOf("```");
            if (fence >= 0) {
                s = s.substring(0, fence);
            }
        }
        return s.trim();
    }
}
