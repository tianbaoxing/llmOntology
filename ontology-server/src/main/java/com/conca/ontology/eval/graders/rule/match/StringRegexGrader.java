package com.conca.ontology.eval.graders.rule.match;

import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.springframework.stereotype.Component;

import com.conca.ontology.eval.Grader;
import com.conca.ontology.eval.model.GraderInput;
import com.conca.ontology.eval.model.GraderScore;

/**
 * 正则匹配评测（二期规则补强，对齐 OpenJudge StringMatchGrader 的 regex 模式）：
 * reference_response 为正则表达式字符串，回答命中（find 语义）得 1 分，否则 0 分。
 */
@Component
public class StringRegexGrader implements Grader {

    @Override
    public String name() {
        return "string_regex";
    }

    @Override
    public String description() {
        return "正则匹配：reference_response 为正则表达式，回答中命中得 1 分，否则 0 分";
    }

    @Override
    public GraderScore evaluate(GraderInput input) {
        Object ref = input.referenceResponse();
        if (ref == null || String.valueOf(ref).isBlank()) {
            return GraderScore.fail(name(), "[参数错误] reference_response 缺失（须为正则表达式字符串）");
        }
        if (input.record().failed()) {
            return GraderScore.fail(name(), "agent 调用失败：" + input.record().error());
        }
        String pattern = String.valueOf(ref);
        Pattern compiled;
        try {
            compiled = Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            return GraderScore.fail(name(), "[参数错误] reference_response 不是合法正则：" + e.getMessage());
        }
        String answer = input.response() == null ? "" : input.response();
        boolean hit = compiled.matcher(answer).find();
        return GraderScore.of(name(), hit ? 1.0 : 0.0,
                hit ? "正则命中：" + pattern : "正则未命中：" + pattern,
                Map.of("pattern", pattern));
    }
}
