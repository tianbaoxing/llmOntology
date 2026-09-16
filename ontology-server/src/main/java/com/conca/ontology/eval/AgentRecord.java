package com.conca.ontology.eval;

import java.util.List;
import java.util.Map;

/**
 * 单样本的 agent 运行记录（EvalService 订阅 HarnessAgent 事件流收集）：
 * answer = 最终回答全文（多轮样本为最后一轮回答）；toolCalls = {name, args, result, state} 列表
 * （args/result 截断，多轮样本为全轮次拼接）；rounds = 模型调用轮次（多轮累计）；
 * tokens 为累计值；error 非空表示本次调用失败（answer 为空）。
 * dialogue = 多轮对话记录（{query, answer}，单轮样本为空列表），供多轮评测器
 * （context_memory 等）作为「跨轮上下文」证据。
 */
public record AgentRecord(
        String answer,
        List<Map<String, Object>> toolCalls,
        int rounds,
        long promptTokens,
        long completionTokens,
        long durationMs,
        String error,
        List<Map<String, Object>> dialogue) {

    /** 单轮便捷构造（dialogue 为空，兼容既有调用点） */
    public AgentRecord(String answer, List<Map<String, Object>> toolCalls, int rounds,
            long promptTokens, long completionTokens, long durationMs, String error) {
        this(answer, toolCalls, rounds, promptTokens, completionTokens, durationMs, error, List.of());
    }

    public boolean failed() {
        return error != null && !error.isBlank();
    }
}
