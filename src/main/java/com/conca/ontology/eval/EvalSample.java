package com.conca.ontology.eval;

import java.util.List;

/**
 * 数据集样本（data/eval/datasets/*.jsonl 的一行），格式对齐 OpenJudge 扁平数据集：
 * query=问题；response=预置回答（可选，非空则纯判分跳过 agent）；
 * referenceResponse=参考答案（OpenJudge reference_response，字符串/数字/数组由各评测器自行解释）；
 * tolerance=数字比对容差（可选）。样本只承载数据，不指定评测器（评测器仅由任务级勾选决定）。
 * 多轮样本（可选）：turns=[{query, response?}, ...] 多轮脚本，非空时覆盖单轮字段——
 * agent 样本按轮序在同一会话内连续作答（跨轮上下文记忆），被评测对象为最后一轮回答；
 * 全部轮次预置 response 时为纯判分模式。turns 与 query/response 不可混用（以 turns 为准）。
 */
public record EvalSample(
        String id,
        String query,
        String response,
        Object referenceResponse,
        Double tolerance,
        String rubric,
        List<Turn> turns) {

    /** 多轮脚本中的一轮：query 必填；response 预置回答（可选，全轮预置时纯判分） */
    public record Turn(String query, String response) {
    }

    /** 多轮样本（turns 非空） */
    public boolean isMultiTurn() {
        return turns != null && !turns.isEmpty();
    }

    /** 是否预置了回答（纯判分模式，对齐 OpenJudge：评已收集的回答，不驱动 agent） */
    public boolean hasPresetResponse() {
        if (isMultiTurn()) {
            // 多轮样本须整脚本预置（部分预置无法注入 agent 会话中间轮），否则驱动 agent
            return !turns.isEmpty() && turns.stream().allMatch(t -> t.response() != null && !t.response().isBlank());
        }
        return response != null && !response.isBlank();
    }
}
