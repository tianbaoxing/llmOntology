package com.conca.ontology.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;

/**
 * 驾驶舱统计：只读遍历框架 agent-state（HarnessAgent 轮末自动落盘），
 * 聚合 token 用量（ASSISTANT 消息 usage：input/output/cached）与调用量
 * （USER 消息数 = 提问轮次，ASSISTANT 消息数 = LLM 调用次数），按日 / 按会话分组。
 * 统计口径为指定用户自己的用量（userId 由 Controller 从登录态传入）。
 * 未配置 OPENROUTER_API_KEY（agent 未装配）时返回全零空统计。
 */
@Service
public class DashboardService {

    /** 框架 ReActAgent 落盘 state 用的 key（JsonFileAgentStateStore 对应 agent_state.json） */
    static final String STATE_KEY = "agent_state";

    private final ObjectProvider<HarnessAgent> agentProvider;

    public DashboardService(ObjectProvider<HarnessAgent> agentProvider) {
        this.agentProvider = agentProvider;
    }

    /** 全量统计：summary（总量与今日）/ daily（按日聚合，升序）/ sessions（会话明细，最近活动倒序） */
    public Map<String, Object> stats(String userId) {
        AgentStateStore store = storeOrNull();
        if (store == null) {
            return Map.of("summary", emptySummary(), "daily", List.of(), "sessions", List.of());
        }

        List<Map<String, Object>> sessions = new ArrayList<>();
        // date → [inputTokens, outputTokens, tokens, rounds, llmCalls]
        Map<String, long[]> daily = new TreeMap<>();
        long inTotal = 0, outTotal = 0, cachedTotal = 0, roundsTotal = 0, llmCallsTotal = 0;

        for (String id : store.listSessionIds(userId)) {
            AgentState state = store.get(userId, id, STATE_KEY, AgentState.class).orElse(null);
            if (state == null) {
                continue;
            }
            long inSum = 0, outSum = 0, cachedSum = 0, rounds = 0, llmCalls = 0;
            String lastTime = "";
            if (state.getContext() != null) {
                for (Msg msg : state.getContext()) {
                    String day = dayOf(msg.getTimestamp());
                    if (msg.getRole() == MsgRole.USER) {
                        rounds++;
                        if (day != null) {
                            daily.computeIfAbsent(day, k -> new long[5])[3]++;
                        }
                    } else if (msg.getRole() == MsgRole.ASSISTANT) {
                        llmCalls++;
                        ChatUsage usage = msg.getUsage();
                        if (usage != null) {
                            inSum += usage.getInputTokens();
                            outSum += usage.getOutputTokens();
                            cachedSum += usage.getCachedTokens();
                            if (day != null) {
                                long[] d = daily.computeIfAbsent(day, k -> new long[5]);
                                d[0] += usage.getInputTokens();
                                d[1] += usage.getOutputTokens();
                                d[2] += usage.getInputTokens() + usage.getOutputTokens();
                            }
                        }
                        if (day != null) {
                            daily.computeIfAbsent(day, k -> new long[5])[4]++;
                        }
                    }
                    if (msg.getTimestamp() != null && !msg.getTimestamp().isBlank()) {
                        lastTime = msg.getTimestamp();
                    }
                }
            }
            inTotal += inSum;
            outTotal += outSum;
            cachedTotal += cachedSum;
            roundsTotal += rounds;
            llmCallsTotal += llmCalls;

            Map<String, Object> s = new LinkedHashMap<>();
            s.put("sessionId", id);
            s.put("title", AgentSessionService.titleOf(state));
            s.put("lastTime", lastTime);
            s.put("rounds", rounds);
            s.put("llmCalls", llmCalls);
            s.put("inputTokens", inSum);
            s.put("outputTokens", outSum);
            s.put("cachedTokens", cachedSum);
            s.put("tokens", inSum + outSum);
            sessions.add(s);
        }
        sessions.sort(Comparator.comparing(
                s -> String.valueOf(s.get("lastTime")), Comparator.reverseOrder()));

        String today = LocalDate.now().toString();
        long todayTokens = daily.containsKey(today) ? daily.get(today)[2] : 0;

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("sessions", sessions.size());
        summary.put("rounds", roundsTotal);
        summary.put("llmCalls", llmCallsTotal);
        summary.put("inputTokens", inTotal);
        summary.put("outputTokens", outTotal);
        summary.put("cachedTokens", cachedTotal);
        summary.put("totalTokens", inTotal + outTotal);
        summary.put("todayTokens", todayTokens);

        List<Map<String, Object>> dailyList = new ArrayList<>();
        for (Map.Entry<String, long[]> e : daily.entrySet()) {
            long[] v = e.getValue();
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("date", e.getKey());
            d.put("inputTokens", v[0]);
            d.put("outputTokens", v[1]);
            d.put("tokens", v[2]);
            d.put("rounds", v[3]);
            d.put("llmCalls", v[4]);
            dailyList.add(d);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", summary);
        result.put("daily", dailyList);
        result.put("sessions", sessions);
        return result;
    }

    // ---------------- 内部 ----------------

    private AgentStateStore storeOrNull() {
        HarnessAgent agent = agentProvider.getIfAvailable();
        return agent == null ? null : agent.getStateStore();
    }

    private static Map<String, Object> emptySummary() {
        return Map.of("sessions", 0, "rounds", 0, "llmCalls", 0,
                "inputTokens", 0, "outputTokens", 0, "cachedTokens", 0,
                "totalTokens", 0, "todayTokens", 0);
    }

    /** timestamp 形如 "2026-08-20 15:43:18.432"，取日期段；格式不符返回 null（不计入按日统计） */
    private static String dayOf(String ts) {
        if (ts == null || ts.length() < 10) {
            return null;
        }
        return ts.substring(0, 10);
    }
}
