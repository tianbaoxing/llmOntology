package com.conca.ontology.eval;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * LLM judge 通道（二期，LlmJudgeGrader 的模型调用层）：
 * OpenAI 兼容 /chat/completions 直连 OpenRouter，复用 app.agent 的
 * base-url/api-key/model，可用 app.eval.judge.* 独立覆盖（评测换更便宜/更强的模型）。
 * 失败（HTTP 非 2xx / 输出不可解析）重试 1 次，仍失败返回 ok=false（调用方记 GraderError）；
 * token 用量按全部尝试累计（真实成本口径）。
 */
@Component
public class JudgeClient {

    private static final Logger log = LoggerFactory.getLogger(JudgeClient.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    @Value("${app.eval.judge.base-url:${app.agent.base-url:}}")
    private String baseUrl;

    @Value("${app.eval.judge.api-key:${app.agent.api-key:}}")
    private String apiKey;

    @Value("${app.eval.judge.model:${app.agent.model:}}")
    private String model;

    /** 单次判分输出上限（reason 保持简短即可，控成本） */
    @Value("${app.eval.judge.max-output-tokens:400}")
    private int maxOutputTokens;

    @Value("${app.eval.judge.timeout-seconds:60}")
    private int timeoutSeconds;

    /** 判分次数（>1 时多次取平均降噪，对齐 OpenJudge AverageEvaluationStrategy；默认 1） */
    @Value("${app.eval.judge.runs:1}")
    private int runs;

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank()
                && baseUrl != null && !baseUrl.isBlank()
                && model != null && !model.isBlank();
    }

    public int runs() {
        return Math.max(1, runs);
    }

    /** 单次判分结果：ok=false 时 error 有值（重试 1 次后仍失败）；token 为全部尝试累计 */
    public record JudgeCall(boolean ok, double rawScore, String reason, String model,
            long promptTokens, long completionTokens, int attempts, long durationMs, String error) {
    }

    /**
     * 调用 judge：要求输出 {"score": 1-5, "reason": "..."}。
     * 解析容忍 markdown 围栏与前后杂文（截取首个 { 到末个 }）；score 缺失或非数字视为
     * 解析失败进入重试；数字越界收敛到 [1,5]。
     */
    public JudgeCall call(String systemPrompt, String userPrompt) {
        long t0 = System.currentTimeMillis();
        long promptTokens = 0;
        long completionTokens = 0;
        int attempts = 0;
        String lastError = null;
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
            try {
                ObjectNode body = mapper.createObjectNode();
                body.put("model", model);
                body.put("temperature", 0);
                body.put("max_tokens", maxOutputTokens);
                ArrayNode messages = body.putArray("messages");
                messages.addObject().put("role", "system").put("content", systemPrompt);
                messages.addObject().put("role", "user").put("content", userPrompt);
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl.replaceAll("/+$", "") + "/chat/completions"))
                        .timeout(Duration.ofSeconds(timeoutSeconds))
                        .header("Authorization", "Bearer " + apiKey)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                        .build();
                HttpResponse<String> resp = http.send(req,
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                JsonNode root = mapper.readTree(resp.body());
                JsonNode usage = root.path("usage");
                promptTokens += usage.path("prompt_tokens").asLong(0);
                completionTokens += usage.path("completion_tokens").asLong(0);
                if (resp.statusCode() / 100 != 2) {
                    lastError = "HTTP " + resp.statusCode() + "："
                            + root.path("error").path("message").asText(brief(resp.body()));
                    continue;
                }
                String content = root.path("choices").path(0).path("message").path("content").asText("");
                JsonNode judged = parseJudgeJson(content);
                if (judged == null || !judged.path("score").isNumber()) {
                    lastError = "judge 输出无法解析为 {score, reason}：" + brief(content);
                    continue;
                }
                double raw = clamp(judged.path("score").asDouble());
                return new JudgeCall(true, raw, judged.path("reason").asText(""), model,
                        promptTokens, completionTokens, attempts,
                        System.currentTimeMillis() - t0, null);
            } catch (Exception e) {
                lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                log.warn("judge 调用第 {} 次尝试失败：{}", attempts, lastError);
            }
        }
        return new JudgeCall(false, 0, null, model, promptTokens, completionTokens,
                attempts, System.currentTimeMillis() - t0, lastError);
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
