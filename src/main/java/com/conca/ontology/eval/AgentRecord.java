package com.conca.ontology.eval;

import java.util.List;
import java.util.Map;

/**
 * 单样本的 agent 运行记录（EvalService 订阅 HarnessAgent 事件流收集）：
 * answer = 最终回答全文；toolCalls = {name, args, result, state} 列表（args/result 截断）；
 * rounds = 模型调用轮次；tokens 为累计值；error 非空表示本次调用失败（answer 为空）。
 */
public record AgentRecord(
        String answer,
        List<Map<String, Object>> toolCalls,
        int rounds,
        long promptTokens,
        long completionTokens,
        long durationMs,
        String error) {

    public boolean failed() {
        return error != null && !error.isBlank();
    }
}
