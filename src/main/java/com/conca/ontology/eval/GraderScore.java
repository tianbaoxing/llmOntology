package com.conca.ontology.eval;

import java.util.Map;

/**
 * 评测器统一结果（对齐 OpenJudge GraderScore/GraderError）：
 * score ∈ [0,1]（一期规则评测器全部 0-1）；error=true 表示评测过程本身失败
 * （如 Neo4j 不可达、数据行缺字段），不计入得分统计而计入 errorCount。
 */
public record GraderScore(
        String name,
        double score,
        String reason,
        boolean error,
        Map<String, Object> metadata) {

    public static GraderScore of(String name, double score, String reason, Map<String, Object> metadata) {
        return new GraderScore(name, score, reason, false, metadata == null ? Map.of() : metadata);
    }

    public static GraderScore fail(String name, String reason) {
        return new GraderScore(name, 0.0, reason, true, Map.of());
    }
}
