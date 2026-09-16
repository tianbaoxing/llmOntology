package com.conca.ontology.eval.graders.rule.match;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.conca.ontology.eval.Grader;
import com.conca.ontology.eval.GraderInput;
import com.conca.ontology.eval.GraderScore;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * JSON 语法校验评测（对齐 OpenJudge 启用即校验的哲学）：样本 graders 含本评测器即校验，
 * 无需参考答案。回答剥离 markdown 代码围栏后须可解析为合法 JSON。
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
        return "JSON 语法校验：启用即校验（无需参考答案），回答（剥离 markdown 围栏后）须为合法 JSON，用于要求结构化输出的问题";
    }

    @Override
    public GraderScore evaluate(GraderInput input) {
        if (input.record().failed()) {
            return GraderScore.fail(name(), "agent 调用失败：" + input.record().error());
        }
        String answer = stripFence(input.response() == null ? "" : input.response().trim());
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
