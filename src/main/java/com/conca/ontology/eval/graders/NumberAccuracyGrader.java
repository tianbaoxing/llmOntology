package com.conca.ontology.eval.graders;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.conca.ontology.eval.AgentRecord;
import com.conca.ontology.eval.EvalSample;
import com.conca.ontology.eval.Grader;
import com.conca.ontology.eval.GraderScore;

/**
 * 数字准确性评测（对齐 OpenJudge NumberAccuracyGrader）：
 * expected = {type:"number", value:123 或 [1,23], tolerance:0}。
 * 从回答中提取全部数字，逐个参考数字检查回答数字中是否存在容差内匹配，
 * score = 命中参考数字数 / 参考数字总数。
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
        return "数字准确性：从回答中提取全部数字，与 expected.value 中的参考数字逐一容差比对，按命中比例得分";
    }

    @Override
    public GraderScore evaluate(EvalSample sample, AgentRecord record) {
        Map<String, Object> expected = sample.expected();
        List<Double> refs = toDoubleList(expected == null ? null : expected.get("value"));
        if (refs.isEmpty()) {
            return GraderScore.fail(name(), "[参数错误] expected.value 缺失或无有效数字，无法判分");
        }
        double tolerance = expected.get("tolerance") instanceof Number n ? n.doubleValue() : 0.0;
        if (record.failed()) {
            return GraderScore.fail(name(), "agent 调用失败：" + record.error());
        }
        String answer = record.answer() == null ? "" : record.answer();
        List<Double> answerNumbers = extractNumbers(answer);

        List<Double> missed = new ArrayList<>();
        for (double ref : refs) {
            if (answerNumbers.stream().noneMatch(a -> Math.abs(a - ref) <= tolerance)) {
                missed.add(ref);
            }
        }
        double score = (refs.size() - missed.size()) * 1.0 / refs.size();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("refNumbers", refs);
        meta.put("answerNumbers", answerNumbers);
        meta.put("tolerance", tolerance);
        String reason;
        if (answer.isBlank()) {
            reason = "回答为空，未提取到任何数字";
        } else if (answerNumbers.isEmpty()) {
            reason = "回答中未提取到数字（共 " + refs.size() + " 个参考数字全部未命中）";
        } else {
            reason = missed.isEmpty() ? "全部 " + refs.size() + " 个参考数字均已命中"
                    : "参考数字命中 " + (refs.size() - missed.size()) + "/" + refs.size()
                            + "，未命中：" + missed + "（回答中的数字：" + answerNumbers + "）";
        }
        return GraderScore.of(name(), score, reason, meta);
    }

    /** 提取回答中的全部数字（含小数，负号仅在前导时算入；先合并千位分隔符如 1,017 → 1017） */
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

    /** value 兼容单数字 / 数字数组 */
    static List<Double> toDoubleList(Object value) {
        List<Double> list = new ArrayList<>();
        if (value instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Number n) {
                    list.add(n.doubleValue());
                } else if (o != null) {
                    try {
                        list.add(Double.parseDouble(String.valueOf(o)));
                    } catch (NumberFormatException ignore) {
                        // 非数字元素跳过
                    }
                }
            }
        } else if (value instanceof Number n) {
            list.add(n.doubleValue());
        } else if (value != null) {
            try {
                list.add(Double.parseDouble(String.valueOf(value)));
            } catch (NumberFormatException ignore) {
                // 非数字跳过
            }
        }
        return list;
    }
}
