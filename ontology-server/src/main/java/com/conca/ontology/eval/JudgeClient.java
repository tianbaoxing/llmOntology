package com.conca.ontology.eval;

import java.time.Duration;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.harness.agent.HarnessAgent;

/**
 * LLM judge 通道（二期重构：裸 HTTP 直连 OpenRouter 切换为 judge 专用 HarnessAgent）：
 * 模型调用经由 JudgeAgentHolder 持有的纯 LLM agent（无工具/技能/子代理），
 * 每次判分独立 sessionId（userId=eval-judge），会话留痕落 judge-state 目录、
 * AgentTraceMiddleware 输出调用日志——补齐此前旁路调用缺失的 LLM 日志监控。
 * 配置沿用 app.eval.judge.*（缺省回落 app.agent.*）。
 * 失败（调用异常 / 输出不可解析）重试 1 次（3s 退避），仍失败返回 ok=false
 * （调用方记 GraderError）；token 用量按全部尝试累计（真实成本口径）。
 */
@Component
public class JudgeClient {

    private static final Logger log = LoggerFactory.getLogger(JudgeClient.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final JudgeAgentHolder agentHolder;

    @Value("${app.eval.judge.base-url:${app.agent.base-url:}}")
    private String baseUrl;

    @Value("${app.eval.judge.api-key:${app.agent.api-key:}}")
    private String apiKey;

    @Value("${app.eval.judge.model:${app.agent.model:}}")
    private String model;

    @Value("${app.eval.judge.timeout-seconds:60}")
    private int timeoutSeconds;

    /** 判分次数（>1 时多次取平均降噪，对齐 OpenJudge AverageEvaluationStrategy；默认 1） */
    @Value("${app.eval.judge.runs:1}")
    private int runs;

    public JudgeClient(JudgeAgentHolder agentHolder) {
        this.agentHolder = agentHolder;
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank()
                && baseUrl != null && !baseUrl.isBlank()
                && model != null && !model.isBlank();
    }

    public int runs() {
        return Math.max(1, runs);
    }

    /** 单次判分结果：ok=false 时 error 有值（重试 1 次后仍失败）；token 为全部尝试累计；sessionId 供留痕回查 */
    public record JudgeCall(boolean ok, double rawScore, String reason, String model,
            long promptTokens, long completionTokens, int attempts, long durationMs,
            String error, String sessionId) {
    }

    /**
     * 调用 judge：userPrompt 已含【评分标准】等全部区块（仅输出 JSON 的守则固定在
     * agent sysPrompt）。解析容忍 markdown 围栏与前后杂文（截取首个 { 到末个 }）；
     * score 缺失或非数字视为解析失败进入重试；数字越界收敛到 [1,5]。
     */
    public JudgeCall call(String userPrompt) {
        HarnessAgent agent = agentHolder.agent();
        long t0 = System.currentTimeMillis();
        long promptTokens = 0;
        long completionTokens = 0;
        int attempts = 0;
        String lastError = null;
        String sessionId = null;
        for (int i = 0; i < 2; i++) { // 首次 + 重试 1 次（对齐设计方案）
            attempts++;
            if (i > 0) {
                try {
                    Thread.sleep(3000); // 限流（如 OpenRouter "Rate exceeded"）短退避后重试，立即重试大概率再次限流
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (agent == null) {
                lastError = "judge agent 未装配（key 未配置或初始化失败）";
                break;
            }
            // 每次判分独立会话：样本间上下文零串扰，且每次调用在 judge-state 留下可回查的完整记录
            sessionId = "judge-" + UUID.randomUUID().toString().substring(0, 8);
            try {
                Msg userMsg = Msg.builder().role(MsgRole.USER).textContent(userPrompt).build();
                RuntimeContext ctx = RuntimeContext.builder()
                        .userId("eval-judge")
                        .sessionId(sessionId)
                        .build();
                Msg reply = agent.call(userMsg, ctx)
                        .block(Duration.ofSeconds(timeoutSeconds + 5L)); // 模型层已设超时，此处兜底防悬挂
                ChatUsage usage = reply == null ? null : reply.getUsage();
                if (usage != null) {
                    promptTokens += usage.getInputTokens();
                    completionTokens += usage.getOutputTokens();
                }
                String content = reply == null ? "" : reply.getTextContent();
                JsonNode judged = parseJudgeJson(content);
                if (judged == null || !judged.path("score").isNumber()) {
                    lastError = "judge 输出无法解析为 {score, reason}：" + brief(content);
                    continue;
                }
                double raw = clamp(judged.path("score").asDouble());
                return new JudgeCall(true, raw, judged.path("reason").asText(""), model,
                        promptTokens, completionTokens, attempts,
                        System.currentTimeMillis() - t0, null, sessionId);
            } catch (Exception e) {
                lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                log.warn("judge 调用第 {} 次尝试失败（session={}）：{}", attempts, sessionId, lastError);
            }
        }
        return new JudgeCall(false, 0, null, model, promptTokens, completionTokens,
                attempts, System.currentTimeMillis() - t0, lastError, sessionId);
    }

    /** 截取首个 { 到末个 } 之间解析；须含 score 字段 */
    private JsonNode parseJudgeJson(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        int l = content.indexOf('{');
        int r = content.lastIndexOf('}');
        if (l < 0 || r <= l) {
            return null;
        }
        try {
            return mapper.readTree(content.substring(l, r + 1));
        } catch (Exception e) {
            return null;
        }
    }

    private static double clamp(double v) {
        return Math.max(1.0, Math.min(5.0, v));
    }

    private static String brief(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 120 ? s : s.substring(0, 120) + "…";
    }
}
