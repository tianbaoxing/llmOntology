package com.conca.ontology.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import com.conca.ontology.shared.web.ApiException;
import com.conca.ontology.skill.SkillUsageJudge;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.harness.agent.HarnessAgent;

/**
 * 会话详情：驾驶舱「会话用量明细」下钻，只读遍历框架 agent-state。
 * - summary：全会话累计用量（口径与 DashboardService 一致）
 * - breakdown：最后一次 LLM 调用实际输入的上下文构成（系统提示词 / 工具 Schema /
 *   用户消息 / 助手消息 / 注入上下文 / 工具结果），Token 为字符启发式估算
 *   （CJK ≈1 token/字，其余 ≈4 字符/token），有真实 inputTokens 锚点时等比例校准
 * - messages：全会话消息明细（不截断）
 * 系统提示词与工具 Schema 运行时实时读取（agent-state 不落盘这两项），与模型实际可见一致。
 */
@Service
public class SessionDetailService {

    /** 框架 ReActAgent 落盘 state 用的 key（与 DashboardService/AgentSessionService 一致） */
    static final String STATE_KEY = "agent_state";
    /** 与框架同款 sessionId 合法字符集，先挡路径穿越 */
    private static final Pattern SESSION_ID = Pattern.compile("^[a-zA-Z0-9_\\-.]+$");

    private final ObjectProvider<HarnessAgent> agentProvider;
    private final AgentSessionService agentSessionService;
    private final ObjectMapper mapper;
    private final SkillUsageJudge skillUsageJudge;

    public SessionDetailService(ObjectProvider<HarnessAgent> agentProvider,
            AgentSessionService agentSessionService, ObjectMapper mapper,
            SkillUsageJudge skillUsageJudge) {
        this.agentProvider = agentProvider;
        this.agentSessionService = agentSessionService;
        this.mapper = mapper;
        this.skillUsageJudge = skillUsageJudge;
    }

    /** 单会话详情：summary 全会话累计 / breakdown 最后一次调用输入构成 / messages 全量明细 */
    public Map<String, Object> detail(String userId, String sessionId) {
        String id = validate(sessionId);
        HarnessAgent agent = agentProvider.getIfAvailable();
        if (agent == null) {
            throw new ApiException(503, AgentChatService.missingKeyMessage());
        }
        AgentState state = agent.getStateStore()
                .get(userId, id, STATE_KEY, AgentState.class)
                .orElseThrow(() -> new ApiException(404, "会话不存在"));
        List<Msg> context = state.getContext() == null ? List.of() : state.getContext();

        // ---- 静态上下文：系统提示词 + 工具 Schema（每次调用均携带，运行时实时读取）----
        String sysPrompt = agent.getDelegate().getSysPrompt();
        StringBuilder schemaJson = new StringBuilder();
        List<Map<String, Object>> toolSchemas = new ArrayList<>();
        List<String> toolNames = new ArrayList<>(agent.getToolkit().getToolNames());
        Collections.sort(toolNames);
        for (String name : toolNames) {
            AgentTool tool = agent.getToolkit().getTool(name);
            if (tool == null) {
                continue;
            }
            String desc = tool.getDescription() == null ? "" : tool.getDescription();
            Map<String, Object> parameters = tool.getParameters();
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("name", name);
            t.put("description", desc);
            t.put("parameters", parameters);
            t.put("estTokens", estimateTokens(toJson(t)));
            toolSchemas.add(t);
            schemaJson.append(toJson(t)).append('\n');
        }
        int toolCount = toolSchemas.size();

        // ---- 最后一次 LLM 调用定位（其输入 = 系统提示词 + 工具 Schema + 此前全部消息）----
        int lastUsageIdx = -1;
        for (int i = 0; i < context.size(); i++) {
            Msg m = context.get(i);
            if (m.getRole() == MsgRole.ASSISTANT && m.getUsage() != null) {
                lastUsageIdx = i;
            }
        }

        // ---- 全会话汇总 + 消息明细 + 分类计数（仅统计最后一次调用之前的消息）----
        long rounds = 0, llmCalls = 0, inTotal = 0, outTotal = 0, cachedTotal = 0;
        long userItems = 0, userEst = 0, assistantItems = 0, assistantEst = 0;
        long injectedItems = 0, injectedEst = 0, toolResultItems = 0, toolResultEst = 0;
        String lastTime = "";
        List<Map<String, Object>> messages = new ArrayList<>();
        Map<String, Map<String, Object>> pendingTools = new LinkedHashMap<>();

        for (int i = 0; i < context.size(); i++) {
            Msg msg = context.get(i);
            if (AgentSessionService.COMPACTION_SUMMARY_NAME.equals(msg.getName())) {
                continue; // 压缩摘要由摘要卡片渲染，不作为 USER 气泡（与 chat 历史一致）
            }
            boolean beforeLastCall = lastUsageIdx < 0 || i < lastUsageIdx;
            if (msg.getTimestamp() != null && !msg.getTimestamp().isBlank()) {
                lastTime = msg.getTimestamp();
            }
            if (msg.getRole() == MsgRole.USER) {
                rounds++;
                // 带改写 trace 的消息：state 存改写后文本，明细还原用户原句（与 chat 历史一致）
                RewriteConfigService.RewriteTrace trace = RewriteConfigService.traceOf(msg);
                String content = trace != null ? trace.original() : textOf(msg);
                long est = estimateTokens(content);
                if (beforeLastCall) {
                    userItems++;
                    userEst += est;
                }

                
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("role", "user");
                m.put("ts", msg.getTimestamp());
                m.put("content", content);
                m.put("estTokens", est);
                messages.add(m);

                if (trace != null) {
                    messages.add(rewriteCard(trace, msg.getTimestamp()));
                }
            } else if (msg.getRole() == MsgRole.ASSISTANT) {
                llmCalls++;
                StringBuilder thinking = new StringBuilder();
                StringBuilder answer = new StringBuilder();
                List<Map<String, Object>> tools = new ArrayList<>();
                long est = 0;
                for (ContentBlock b : msg.getContent()) {
                    if (b instanceof ThinkingBlock tb) {
                        thinking.append(tb.getThinking());
                    } else if (b instanceof TextBlock tb) {
                        answer.append(tb.getText());
                    } else if (b instanceof ToolUseBlock tb) {
                        String inputJson = toJson(tb.getInput());
                        Map<String, Object> t = new LinkedHashMap<>();
                        t.put("name", tb.getName());
                        t.put("input", inputJson);
                        tools.add(t);
                        pendingTools.put(tb.getId(), t);
                        est += estimateTokens(inputJson) + estimateTokens(tb.getName());
                    }
                }
                est += estimateTokens(thinking.toString()) + estimateTokens(answer.toString());
                if (beforeLastCall) {
                    assistantItems++;
                    assistantEst += est;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("role", "assistant");
                m.put("ts", msg.getTimestamp());
                m.put("thinking", thinking.toString());
                m.put("answer", answer.toString());
                m.put("tools", tools);
                m.put("estTokens", est);
                ChatUsage usage = msg.getUsage();
                if (usage != null) {
                    inTotal += usage.getInputTokens();
                    outTotal += usage.getOutputTokens();
                    cachedTotal += usage.getCachedTokens();
                    m.put("promptTokens", usage.getInputTokens());
                    m.put("completionTokens", usage.getOutputTokens());
                    m.put("cachedTokens", usage.getCachedTokens());
                }
                messages.add(m);
            } else if (msg.getRole() == MsgRole.TOOL) {
                for (ContentBlock b : msg.getContent()) {
                    if (!(b instanceof ToolResultBlock tb)) {
                        continue;
                    }
                    StringBuilder out = new StringBuilder();
                    for (ContentBlock ob : tb.getOutput()) {
                        if (ob instanceof TextBlock tx) {
                            out.append(tx.getText());
                        }
                    }
                    String output = out.toString();
                    long est = estimateTokens(output);
                    if (beforeLastCall) {
                        toolResultItems++;
                        toolResultEst += est;
                    }
                    Map<String, Object> t = pendingTools.get(tb.getId());
                    if (t != null) {
                        t.put("output", output);
                        t.put("outputTokens", est);
                    }
                }
            } else if (msg.getRole() == MsgRole.SYSTEM) {
                String content = textOf(msg);
                long est = estimateTokens(content);
                if (beforeLastCall) {
                    injectedItems++;
                    injectedEst += est;
                }
            }
        }

        // ---- 校准：按最后一次调用真实 inputTokens 等比例缩放各分类估算值 ----
        long sysEst = estimateTokens(sysPrompt);
        long schemasEst = estimateTokens(schemaJson.toString());
        long base = sysEst + schemasEst + userEst + assistantEst + injectedEst + toolResultEst;
        Long lastInput = null;
        if (lastUsageIdx >= 0) {
            lastInput = (long) context.get(lastUsageIdx).getUsage().getInputTokens();
        }
        double calibration = 1.0;
        if (lastInput != null && lastInput > 0 && base > 0) {
            calibration = (double) lastInput / base;
        }
        long sysTok = Math.round(sysEst * calibration);
        long schemasTok = Math.round(schemasEst * calibration);
        long userTok = Math.round(userEst * calibration);
        long assistantTok = Math.round(assistantEst * calibration);
        long injectedTok = Math.round(injectedEst * calibration);
        long toolResultTok = Math.round(toolResultEst * calibration);
        long tokTotal = sysTok + schemasTok + userTok + assistantTok + injectedTok + toolResultTok;

        // ---- 压缩历史合并（参照 chat 历史）：state 仅存 [摘要+尾部]，
        // 压缩前消息从会话转录恢复，拼装 [前缀, 摘要卡片, state 尾部] ----
        AgentSessionService.CompactionHistory history =
                agentSessionService.compactionHistory(userId, id, state);
        if (history != null) {
            List<Map<String, Object>> merged = new ArrayList<>(history.prefix());
            merged.add(history.card());
            merged.addAll(messages);
            messages = merged;
        }

        // 明细消息估算值统一按同一系数校准，与图表口径一致（转录恢复的 flat 消息无估算值，跳过）
        for (Map<String, Object> m : messages) {
            Number est = (Number) m.get("estTokens");
            if (est != null) {
                m.put("estTokens", Math.round(est.longValue() * calibration));
            }
        }
        for (Map<String, Object> t : toolSchemas) {
            t.put("estTokens", Math.round(((Number) t.get("estTokens")).longValue() * calibration));
        }

        List<Map<String, Object>> categories = new ArrayList<>();
        categories.add(category("system_prompt", "系统提示词", 1, sysTok, tokTotal));
        categories.add(category("tool_schemas", "工具 Schema", toolCount, schemasTok, tokTotal));
        categories.add(category("user_messages", "用户消息", userItems, userTok, tokTotal));
        categories.add(category("assistant_messages", "助手消息", assistantItems, assistantTok, tokTotal));
        categories.add(category("injected_context", "注入上下文", injectedItems, injectedTok, tokTotal));
        categories.add(category("tool_results", "工具结果", toolResultItems, toolResultTok, tokTotal));

        Map<String, Object> breakdown = new LinkedHashMap<>();
        breakdown.put("lastCallInputTokens", lastInput);
        breakdown.put("estimatedTokens", base);
        breakdown.put("calibratedTokens", tokTotal);
        breakdown.put("calibration", Math.round(calibration * 1000) / 1000.0);
        breakdown.put("toolCount", toolCount);
        breakdown.put("categories", categories);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("rounds", rounds);
        summary.put("llmCalls", llmCalls);
        summary.put("inputTokens", inTotal);
        summary.put("outputTokens", outTotal);
        summary.put("cachedTokens", cachedTotal);
        summary.put("totalTokens", inTotal + outTotal);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", id);
        result.put("title", AgentSessionService.effectiveTitle(agent.getStateStore(), userId, id, state));
        result.put("lastTime", lastTime);
        result.put("summary", summary);
        result.put("breakdown", breakdown);
        // 静态上下文明细（每次 LLM 调用均携带，供前端展示具体内容）
        result.put("systemPrompt", sysPrompt);
        result.put("systemPromptTokens", Math.round(sysEst * calibration));
        result.put("toolSchemas", toolSchemas);
        result.put("messages", messages);
        // 技能正确使用判定：加载 ≠ 使用，按 skill-analysis.json 特征指纹匹配首次加载后的步骤
        result.put("skillUsage", skillUsageJudge.judge(messages));
        return result;
    }

    // ---------------- 内部 ----------------

    private static String validate(String sessionId) {
        if (sessionId == null || !SESSION_ID.matcher(sessionId).matches()) {
            throw new ApiException(400, "非法会话 ID");
        }
        return sessionId;
    }

    /** 字符启发式 token 估算：CJK ≈1 token/字，其余 ≈4 字符/token（中文为主内容误差可接受，用于占比参考） */
    static long estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0, other = 0;
        for (int i = 0; i < text.length(); i++) {
            if (isCjk(text.charAt(i))) {
                cjk++;
            } else {
                other++;
            }
        }
        return cjk + (other + 3L) / 4;
    }

    private static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3400 && c <= 0x4DBF)
                || (c >= 0x3000 && c <= 0x303F) || (c >= 0xFF00 && c <= 0xFFEF);
    }

        /**
     * 改写轮虚拟消息（assistant 形态）：thinking = traceToText（与实时 thinking 同源）、
     * 无 answer 无工具，tokens 取各步聚合。压缩历史转录不含 metadata，改写过程在压缩后记录中缺失。
     */
    private static Map<String, Object> rewriteCard(RewriteConfigService.RewriteTrace trace, String ts) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", "assistant");
        m.put("ts", ts);
        m.put("thinking", RewriteConfigService.traceToText(trace));
        m.put("answer", "");
        m.put("tools", List.of());
        long in = 0;
        long out = 0;
        if (trace.steps() != null) {
            for (RewriteConfigService.PipelineStep s : trace.steps()) {
                in += s.promptTokens();
                out += s.completionTokens();
            }
        }
        m.put("promptTokens", in);
        m.put("completionTokens", out);
        return m;
    }

    private static Map<String, Object> category(String key, String name, long items, long tokens, long total) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("key", key);
        c.put("name", name);
        c.put("items", items);
        c.put("tokens", tokens);
        c.put("pct", total > 0 ? Math.round(tokens * 1000.0 / total) / 10.0 : 0.0);
        return c;
    }

    private static String textOf(Msg msg) {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock b : msg.getContent()) {
            if (b instanceof TextBlock tb) {
                sb.append(tb.getText());
            }
        }
        return sb.toString();
    }

    private String toJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            return String.valueOf(o);
        }
    }
}
