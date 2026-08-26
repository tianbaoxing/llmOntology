package com.conca.ontology.eval;

import java.util.List;

/**
 * 数据集样本（data/eval/datasets/*.jsonl 的一行），格式对齐 OpenJudge 扁平数据集：
 * query=问题；response=预置回答（可选，非空则纯判分跳过 agent）；
 * referenceResponse=参考答案（OpenJudge reference_response，字符串/数字/数组由各评测器自行解释）；
 * tolerance=数字比对容差（可选）；graders 为该样本启用的评测器名（空 = 不限制）。
 */
public record EvalSample(
        String id,
        String query,
        String response,
        Object referenceResponse,
        Double tolerance,
        String rubric,
        List<String> graders) {

    /** 是否预置了回答（纯判分模式，对齐 OpenJudge：评已收集的回答，不驱动 agent） */
    public boolean hasPresetResponse() {
        return response != null && !response.isBlank();
    }
}
