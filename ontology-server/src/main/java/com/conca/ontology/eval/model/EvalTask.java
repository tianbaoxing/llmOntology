package com.conca.ontology.eval.model;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * @author tianbaoxing
 * @date 2026-09-18
 */
public class EvalTask {
    public final String id;
    public final String dataset;
    public final List<String> graderNames;
    public final String userId;
    public final Integer judgeRuns;
    public final LocalDateTime startedAt = LocalDateTime.now();
    public volatile String status = "running";
    public volatile String detail = "准备中";
    public volatile int percent = 0;
    public volatile String error;
    public volatile Map<String, Object> result;
    public volatile String resultFile;

    public EvalTask(String id, String dataset, List<String> graderNames, Integer judgeRuns, String userId) {
        this.id = id;
        this.dataset = dataset;
        this.graderNames = graderNames;
        this.judgeRuns = judgeRuns;
        this.userId = userId;
    }
}
