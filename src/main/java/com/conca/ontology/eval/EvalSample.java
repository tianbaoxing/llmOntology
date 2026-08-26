package com.conca.ontology.eval;

import java.util.List;
import java.util.Map;

/**
 * 数据集样本（data/eval/datasets/*.jsonl 的一行）：
 * expected.type 为判分方式提示（exact/contains/number/cypher/json），
 * 具体字段由各评测器自行解释；graders 为该样本启用的评测器名（空 = 全部）。
 */
public record EvalSample(
        String id,
        String query,
        Map<String, Object> expected,
        String rubric,
        List<String> graders) {
}
