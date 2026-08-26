package com.conca.ontology.eval;

import java.util.Map;

/**
 * 评测器入参参数包（对齐 OpenJudge「mapper 之后的 kwargs」）：
 * 由 EvalService.judge() 按 mapper 映射表组装（数据集字段 → grader 参数名），
 * grader 只按参数名取值，不感知数据集 schema。参数名对齐 OpenJudge grader
 * 的 aevaluate() 形参：query / response / reference_response / tolerance / rubric。
 * record 携带 agent 运行轨迹（toolCalls/rounds/tokens，为二期轨迹类评测器备料；
 * 预置 response 样本为占位 record，仅 answer 有值）。
 */
public record GraderInput(Map<String, Object> params, AgentRecord record) {

    public String query() {
        return str("query");
    }

    /** 被评测回答：预置样本取数据集 response，agent 样本取运行时生成的回答 */
    public String response() {
        return str("response");
    }

    /** 参考答案（OpenJudge reference_response）：字符串/数字/数组，由各评测器自行解释 */
    public Object referenceResponse() {
        return params.get("reference_response");
    }

    /** 数字比对容差，缺省 0 */
    public double tolerance() {
        Object v = params.get("tolerance");
        return v instanceof Number n ? n.doubleValue() : 0.0;
    }

    /** LLM 评测器的补充评分细则（二期使用） */
    public String rubric() {
        return str("rubric");
    }

    private String str(String key) {
        Object v = params.get(key);
        return v == null ? null : String.valueOf(v);
    }
}
