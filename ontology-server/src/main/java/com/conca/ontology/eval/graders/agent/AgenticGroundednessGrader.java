package com.conca.ontology.eval.graders.agent;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.conca.ontology.eval.AgenticJudgeHolder;
import com.conca.ontology.eval.Grader;
import com.conca.ontology.eval.model.GraderInput;
import com.conca.ontology.eval.model.GraderScore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.harness.agent.HarnessAgent;

/**
 * Agentic 取证评测器（三期模块一，对齐 OpenJudge AgenticGrader 思路）：
 * judge 本身是带只读图谱工具的 agent——自主拆解回答中的事实断言，逐条调
 * /api/llm/ 只读平面取证后判分。与 LLM 评测器的本质区别：证据不是静态喂入，
 * 而是 judge 主动查证获得，能发现「回答正确但查证路径不存在」的深层问题。
 *
 * 判分语义：score = 证实断言数 / 总断言数（0-1）。agent 自报 score 仅作参考，
 * evidence 非空时按 confirmed/total 确定性重算（每分可由证据复查，验收 C2/C3）。
 * 成本控制：单样本取证查询上限由 EvalProxyTool 强制（超限确定性文案截断），
 * 实际调用记录（path/body/返回摘要）随 metadata 留痕。调用失败重试 1 次（3s 退避）。
 */
@Component
public class AgenticGroundednessGrader implements Grader {

    private static final Logger log = LoggerFactory.getLogger(AgenticGroundednessGrader.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AgenticJudgeHolder holder;

    public AgenticGroundednessGrader(AgenticJudgeHolder holder) {
        this.holder = holder;
    }

    @Override
    public String name() {
        return "agentic_groundedness";
    }

    @Override
    public String description() {
        return "图谱取证核查（Agentic，消耗 tokens 与查询次数）：judge 自主调只读图谱工具逐条核实回答中的"
                + "事实断言，输出可复查的 evidence 证据链（断言/Cypher/返回摘要/是否证实），score=证实断言数/总断言数";
    }

    @Override
    public String type() {
        return "agentic";
    }

    @Override
    public GraderScore evaluate(GraderInput input) {
        if (!holder.isConfigured() || holder.agent() == null) {
            return GraderScore.fail(name(), "[参数错误] agentic judge 未配置（缺少 OPENROUTER_API_KEY）");
        }
        if (input.record().failed()) {
            return GraderScore.fail(name(), "agent 调用失败：" + input.record().error());
        }
        String answer = input.response() == null ? "" : input.response();
        if (answer.isBlank()) {
            return GraderScore.of(name(), 0.0, "回答为空，直接判最低分（不消耗取证调用）", Map.of());
        }

        holder.tool().reset();
        Verdict v = callJudge(input.query(), answer, input.rubric());
        int queryCount = holder.tool().queryCount();
        boolean truncated = queryCount >= holder.maxQueries();

        Map<String, Object> judge = new LinkedHashMap<>();
        judge.put("model", v.model);
        judge.put("promptTokens", v.promptTokens);
        judge.put("completionTokens", v.completionTokens);
        judge.put("calls", v.attempts);
        judge.put("durationMs", v.durationMs);
        judge.put("sessionId", v.sessionId);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("agentic", true);
        meta.put("queryCount", queryCount);
        meta.put("truncated", truncated);
        meta.put("agentScore", v.agentScore);
        meta.put("evidence", v.evidence);
        meta.put("queries", holder.tool().records());
        meta.put("judge", judge);
        appendExpectedMeta(meta, input);

        if (!v.ok) {
            return new GraderScore(name(), 0.0, "[agentic judge 调用失败] " + v.error, true, meta);
        }
        // 确定性重算：证实断言数 / 总断言数（evidence 为空时回落 agent 自报分）
        double score = v.agentScore;
        String reason = v.reason;
        if (v.evidence != null && !v.evidence.isEmpty()) {
            long confirmed = v.evidence.stream()
                    .filter(e -> Boolean.parseBoolean(String.valueOf(e.get("confirmed")))).count();
            score = confirmed / (double) v.evidence.size();
            reason = "证据 " + confirmed + "/" + v.evidence.size() + " 条证实。" + reason;
        }
        if (truncated) {
            reason += "（取证截断：查询次数达上限 " + holder.maxQueries() + "，剩余断言按未证实计）";
        }
        return GraderScore.of(name(), clamp(score), reason, meta);
    }

    /** 单次取证判分结果（ok=false 时 error 有值） */
    private record Verdict(boolean ok, double agentScore, String reason,
            List<Map<String, Object>> evidence, String model,
            long promptTokens, long completionTokens, int attempts, long durationMs,
            String error, String sessionId) {
    }

    /** 驱动取证 agent：失败/输出不可解析重试 1 次（3s 退避，对齐 JudgeClient 语义） */
    private Verdict callJudge(String query, String answer, String rubric) {
        HarnessAgent agent = holder.agent();
        long t0 = System.currentTimeMillis();
        long promptTokens = 0;
        long completionTokens = 0;
        int attempts = 0;
        String lastError = null;
        String sessionId = null;
        for (int i = 0; i < 2; i++) {
            attempts++;
            if (i > 0) {
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                holder.tool().reset(); // 重试从头取证，避免计数翻倍提前截断
            }
            sessionId = "agentic-" + UUID.randomUUID().toString().substring(0, 8);
            try {
                Msg reply = agent.call(
                        Msg.builder().role(MsgRole.USER).textContent(userPrompt(query, answer, rubric)).build(),
                        RuntimeContext.builder().userId("eval-agentic").sessionId(sessionId).build())
                        .block(Duration.ofSeconds(120)); // 多轮取证整体兜底，模型层已有单次超时
                ChatUsage usage = reply == null ? null : reply.getUsage();
                if (usage != null) {
                    promptTokens += usage.getInputTokens();
                    completionTokens += usage.getOutputTokens();
                }
                JsonNode parsed = parseJson(reply == null ? "" : reply.getTextContent());
                if (parsed == null || !parsed.path("score").isNumber()) {
                    lastError = "judge 输出无法解析为 {score, reason, evidence}："
                            + brief(reply == null ? "" : reply.getTextContent());
                    continue;
                }
                List<Map<String, Object>> evidence = new ArrayList<>();
                if (parsed.path("evidence").isArray()) {
                    for (JsonNode e : parsed.path("evidence")) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("claim", e.path("claim").asText(""));
                        m.put("cypher", e.path("cypher").asText(""));
                        m.put("summary", e.path("summary").asText(""));
                        m.put("confirmed", e.path("confirmed").asBoolean(false));
                        evidence.add(m);
                    }
                }
                return new Verdict(true, clamp(parsed.path("score").asDouble()),
                        parsed.path("reason").asText(""), evidence, holder.model(),
                        promptTokens, completionTokens, attempts,
                        System.currentTimeMillis() - t0, null, sessionId);
            } catch (Exception e) {
                lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                log.warn("agentic judge 第 {} 次尝试失败（session={}）：{}", attempts, sessionId, lastError);
            }
        }
        return new Verdict(false, 0, null, null, holder.model(),
                promptTokens, completionTokens, attempts,
                System.currentTimeMillis() - t0, lastError, sessionId);
    }

    private String userPrompt(String query, String answer, String rubric) {
        StringBuilder sb = new StringBuilder();
        sb.append("【原问题】\n").append(query == null || query.isBlank() ? "无" : query).append("\n\n")
                .append("【待核查回答】\n").append(answer);
        if (rubric != null && !rubric.isBlank()) {
            sb.append("\n\n【补充核查细则】\n").append(rubric);
        }
        sb.append("\n\n请拆解上述回答中的事实断言，逐条用工具向图谱取证，然后按系统指令输出 JSON。");
        return sb.toString();
    }

    /** 截取首个 { 到末个 } 之间解析（容忍 markdown 围栏与前后杂文） */
    private static JsonNode parseJson(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        int l = content.indexOf('{');
        int r = content.lastIndexOf('}');
        if (l < 0 || r <= l) {
            return null;
        }
        try {
            return MAPPER.readTree(content.substring(l, r + 1));
        } catch (Exception e) {
            return null;
        }
    }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    private static String brief(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 120 ? s : s.substring(0, 120) + "…";
    }

    private static void appendExpectedMeta(Map<String, Object> meta, GraderInput input) {
        List<String> sk = input.expectedSkills();
        List<String> tl = input.expectedTools();
        List<String> mp = input.expectedMcps();
        if (!sk.isEmpty()) meta.put("expected_skills", sk);
        if (!tl.isEmpty()) meta.put("expected_tools", tl);
        if (!mp.isEmpty()) meta.put("expected_mcps", mp);
    }
}
