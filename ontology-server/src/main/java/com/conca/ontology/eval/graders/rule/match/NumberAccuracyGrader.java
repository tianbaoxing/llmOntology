package com.conca.ontology.eval.graders.rule.match;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.conca.ontology.eval.Grader;
import com.conca.ontology.eval.model.GraderInput;
import com.conca.ontology.eval.model.GraderScore;

/**
 * 数字准确性评测（对齐 OpenJudge NumberAccuracyGrader：顺序逐位比对）：
 * reference_response = 123 / "高15.5，足径15.3" / [15.5,15.3]（数字/文本/数组统一提取数字）。
 * 从回答与参考答案中各提取全部数字，按出现顺序逐位容差比对，
 * score = 命中数 / 参考数字总数；回答多出的数字忽略，缺漏按比例扣分。
 */
@Component
public class NumberAccuracyGrader implements Grader {

    private static final Pattern NUM_RE = Pattern.compile("-?\\d+(?:\\.\\d+)?");

    @Override
    public String name() {
        return "number_accuracy";
    }

    @Override
    public String description() {
        return "数字准确性：从回答与 reference_response 中按顺序提取数字逐位容差比对（对齐 OpenJudge 顺序比对语义），按命中比例得分";
    }

    @Override
    public GraderScore evaluate(GraderInput input) {
        Object ref = input.referenceResponse();
        if (ref == null || String.valueOf(ref).isBlank()) {
            return GraderScore.fail(name(), "[参数错误] reference_response 缺失，无法判分");
        }
        List<Double> refNumbers = extractNumbers(String.valueOf(ref));
        if (refNumbers.isEmpty()) {
            return GraderScore.fail(name(), "[参数错误] reference_response 中未提取到参考数字，无法判分");
        }
        if (input.record().failed()) {
            return GraderScore.fail(name(), "agent 调用失败：" + input.record().error());
        }
        double tolerance = input.tolerance();
        String answer = input.response() == null ? "" : input.response();
        List<Double> answerNumbers = extractNumbers(answer);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("response_numbers", answerNumbers);
        meta.put("reference_response_numbers", refNumbers);
        meta.put("tolerance", tolerance);

        if (answer.isBlank() || answerNumbers.isEmpty()) {
            return GraderScore.of(name(), 0.0,
                    "回答中未提取到数字（共 " + refNumbers.size() + " 个参考数字全部未命中）", meta);
        }

        // 顺序逐位比对：多出的回答数字忽略，比对长度取两者较小值
        int correct = 0;
        int compare = Math.min(answerNumbers.size(), refNumbers.size());
        for (int i = 0; i < compare; i++) {
            if (Math.abs(answerNumbers.get(i) - refNumbers.get(i)) <= tolerance) {
                correct++;
            }
        }
        double accuracy = correct * 1.0 / refNumbers.size();
        meta.put("accuracy", accuracy);
        meta.put("correct_numbers", correct);
        meta.put("total_reference_response_numbers", refNumbers.size());
        String reason = correct == refNumbers.size()
                ? "全部 " + refNumbers.size() + " 个参考数字按顺序全部命中"
                : "参考数字顺序命中 " + correct + "/" + refNumbers.size()
                        + "（参考：" + refNumbers + "，回答：" + answerNumbers + "）";
        return GraderScore.of(name(), accuracy, reason, meta);
    }

    /** 提取文本中的全部数字（含小数，负号仅在前导时算入；先合并千位分隔符如 1,017 → 1017） */
    static List<Double> extractNumbers(String text) {
        List<Double> list = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return list;
        }
        String normalized = text.replaceAll("(\\d),(\\d{3})(?!\\d)", "$1$2");
        Matcher m = NUM_RE.matcher(normalized);
        while (m.find()) {
            try {
                list.add(Double.parseDouble(m.group()));
            } catch (NumberFormatException ignore) {
                // 理论上正则已保证可解析
            }
        }
        return list;
    }
}
