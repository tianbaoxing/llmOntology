package com.conca.ontology.eval.graders.llm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.conca.ontology.eval.AgentRecord;
import com.conca.ontology.eval.Grader;
import com.conca.ontology.eval.GraderInput;
import com.conca.ontology.eval.GraderScore;
import com.conca.ontology.eval.JudgeClient;

/**
 * LLM 评测器基类（二期，对齐 OpenJudge LLMGrader）：
 * 评分标准随用户消息首区块【评分标准】下发（各评测器 criteria 不同；仅输出 JSON
 * 的守则固定在 judge agent sysPrompt）+ 用户模板五变量
 * （query/answer/reference/context/rubric，与数据集字段名解耦）→ JudgeClient
 * （judge 专用 HarnessAgent 通道，会话留痕可回查）→ 解析 {score, reason}，
 * 原始 1-5 分归一化为 0-1（(raw-1)/4），metadata 同时保留 rawScore。
 * 省 token 短路：回答为空直接判 0 分；轨迹类评测器（requireToolCalls）无工具调用时跳过，
 * 均不发起 judge 调用。context = 轨迹中图谱工具返回的拼接（hallucination/groundedness
 * 类「以图谱为证据」核查）。judge 调用失败重试 1 次后仍失败记 GraderError，不影响其它评测器。
 */
public abstract class LlmJudgeGrader implements Grader {

    /** 证据上下文总量与单条工具返回的截断上限（控 judge prompt 长度） */
    private static final int CONTEXT_CHAR_LIMIT = 1200;
    private static final int TOOL_LINE_LIMIT = 200;

    protected final JudgeClient client;

    protected LlmJudgeGrader(JudgeClient client) {
        this.client = client;
    }

    @Override
    public String type() {
        return "llm";
    }

    /** 评分标准（1-5 分语义，子类给出） */
    protected abstract String criteria();

    /** 用户 prompt 追加区块（如工具轨迹，子类按需覆盖） */
    protected String extraBlock(GraderInput input) {
        return "";
    }

    /** 轨迹类评测器返回 true：无工具调用时跳过 judge 调用（无轨迹可评） */
    protected boolean requireToolCalls() {
        return false;
    }

    /** 多轮类评测器返回 true：样本非多轮（对话不足 2 轮）时跳过 judge 调用 */
    protected boolean requireMultiTurn() {
        return false;
    }

    @Override
    public GraderScore evaluate(GraderInput input) {
        if (!client.isConfigured()) {
            return GraderScore.fail(name(), "[参数错误] judge 未配置（缺少 OPENROUTER_API_KEY）");
        }
        if (input.record().failed()) {
            return GraderScore.fail(name(), "agent 调用失败：" + input.record().error());
        }
        String answer = input.response() == null ? "" : input.response();
        if (answer.isBlank()) {
            return GraderScore.of(name(), 0.0, "回答为空，直接判最低分（不消耗 judge 调用）",
                    Map.of("rawScore", 1.0));
        }
        List<Map<String, Object>> toolCalls = input.record().toolCalls();
        if (requireToolCalls() && (toolCalls == null || toolCalls.isEmpty())) {
            return GraderScore.of(name(), 1.0, "未调用工具，无轨迹可评，跳过 judge 调用", Map.of());
        }
        List<Map<String, Object>> dialogue = input.record().dialogue();
        if (requireMultiTurn() && (dialogue == null || dialogue.size() < 2)) {
            return GraderScore.of(name(), 1.0, "非多轮样本（对话不足 2 轮），跳过 judge 调用", Map.of());
        }
        // 判分次数：运行参数（任务级）优先，缺省回落配置 app.eval.judge.runs
        int runs = input.judgeRuns() != null ? Math.max(1, input.judgeRuns()) : client.runs();
        List<Double> rawScores = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        List<String> sessionIds = new ArrayList<>();
        String model = null;
        long promptTokens = 0;
        long completionTokens = 0;
        int calls = 0;
        long durationMs = 0;
        for (int i = 0; i < runs; i++) {
            JudgeClient.JudgeCall c = client.call(userPrompt(input));
            promptTokens += c.promptTokens();
            completionTokens += c.completionTokens();
            calls += c.attempts();
            durationMs += c.durationMs();
            model = c.model();
            if (!c.ok()) {
                return GraderScore.fail(name(), "[judge 调用失败] " + c.error());
            }
            rawScores.add(c.rawScore());
            reasons.add(c.reason());
            if (c.sessionId() != null) {
                sessionIds.add(c.sessionId());
            }
        }
        double avgRaw = rawScores.stream().mapToDouble(Double::doubleValue).average().orElse(1.0);

        Map<String, Object> judge = new LinkedHashMap<>();
        judge.put("runs", runs);
        judge.put("model", model);
        judge.put("promptTokens", promptTokens);
        judge.put("completionTokens", completionTokens);
        judge.put("calls", calls);
        judge.put("durationMs", durationMs);
        judge.put("sessionId", sessionIds.isEmpty() ? null : String.join(",", sessionIds));
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("rawScore", runs == 1 ? rawScores.get(0) : rawScores);
        meta.put("judge", judge);
        String reason = runs == 1 ? reasons.get(0)
                : String.join(" | ", reasons) + "（" + runs + " 次判分取平均）";
        return GraderScore.of(name(), (avgRaw - 1) / 4.0, reason, meta);
    }

    /** 用户模板：首区块评分标准（各评测器不同）+ 五变量（与数据集字段名解耦）：query/answer/reference/context/rubric */
    private String userPrompt(GraderInput input) {
        StringBuilder sb = new StringBuilder();
        sb.append("【评分标准（1-5 整数分制）】\n").append(criteria()).append("\n\n")
                .append("【问题】\n").append(text(input.query())).append("\n\n")
                .append("【待评估回答】\n").append(text(input.response())).append("\n\n")
                .append("【参考答案】\n").append(refText(input.referenceResponse())).append("\n\n")
                .append("【证据上下文（回答所依据的图谱查询返回）】\n").append(contextText(input.record()));
        if (input.rubric() != null && !input.rubric().isBlank()) {
            sb.append("\n\n【补充评分细则】\n").append(input.rubric());
        }
        String extra = extraBlock(input);
        if (extra != null && !extra.isBlank()) {
            sb.append("\n\n").append(extra);
        }
        return sb.toString();
    }

    /** 从轨迹提取图谱工具返回作为证据上下文（每条截断，总量封顶） */
    private String contextText(AgentRecord record) {
        List<Map<String, Object>> toolCalls = record.toolCalls();
        if (toolCalls == null || toolCalls.isEmpty()) {
            return "无";
        }
        StringBuilder sb = new StringBuilder();
        int used = 0;
        for (Map<String, Object> t : toolCalls) {
            String line = "#" + t.getOrDefault("name", "?") + " → "
                    + truncate(String.valueOf(t.getOrDefault("result", "")), TOOL_LINE_LIMIT) + "\n";
            if (used + line.length() > CONTEXT_CHAR_LIMIT) {
                sb.append("（其余工具返回已截断）");
                break;
            }
            sb.append(line);
            used += line.length();
        }
        return sb.toString();
    }

    /** 工具调用轨迹渲染（轨迹类评测器的 extraBlock 使用） */
    protected String renderTrajectory(AgentRecord record) {
        List<Map<String, Object>> toolCalls = record.toolCalls();
        if (toolCalls == null || toolCalls.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("【工具调用轨迹】\n");
        int i = 1;
        for (Map<String, Object> t : toolCalls) {
            sb.append(i++).append(". ").append(t.getOrDefault("name", "?"))
                    .append("（").append(truncate(String.valueOf(t.getOrDefault("args", "")), 150)).append("）→ ")
                    .append(truncate(String.valueOf(t.getOrDefault("result", "")), 150)).append("\n");
        }
        return sb.toString();
    }

    /** 多轮对话渲染（多轮评测器的 extraBlock 使用）：轮次序号 + 每轮问题与回答（各自截断） */
    protected String renderDialogue(AgentRecord record) {
        List<Map<String, Object>> dialogue = record.dialogue();
        if (dialogue == null || dialogue.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("【多轮对话记录（按时间序）】\n");
        int i = 1;
        for (Map<String, Object> t : dialogue) {
            sb.append("第 ").append(i++).append(" 轮\n")
                    .append("问：").append(truncate(String.valueOf(t.getOrDefault("query", "")), 300)).append("\n")
                    .append("答：").append(truncate(String.valueOf(t.getOrDefault("answer", "")), 400)).append("\n");
        }
        return sb.toString();
    }

    /** 参考答案渲染：数组按顿号连接，缺失为「无」 */
    private static String refText(Object ref) {
        if (ref == null) {
            return "无";
        }
        if (ref instanceof List<?> l) {
            return l.isEmpty() ? "无"
                    : l.stream().map(String::valueOf).reduce((a, b) -> a + "、" + b).orElse("无");
        }
        String s = String.valueOf(ref);
        return s.isBlank() ? "无" : s;
    }

    private static String text(String s) {
        return s == null || s.isBlank() ? "无" : s;
    }

    private static String truncate(String s, int limit) {
        if (s == null || s.length() <= limit) {
            return s == null ? "" : s;
        }
        return s.substring(0, limit) + "…";
    }
}
