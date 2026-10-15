package com.conca.ontology.agent.service;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import com.conca.ontology.builder.AgentRegistry;
import com.conca.ontology.shared.web.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.workspace.WorkspaceManager;

/**
 * 历史会话服务：复用框架 agent-state 持久化（HarnessAgent 轮末自动落盘），
 * 只读 + 删除 + 重命名，全部经 AgentStateStore 官方 API，不解析 state 文件本身。
 * 会话标题：手工重命名存独立 key（session_title.json，与 agent_state.json 同目录互不干扰，
 * 删除会话时随目录整体清理）；未手工命名时回退首条用户消息派生标题。
 * 会话被 Compaction 压缩后 state 仅存 [摘要+保留尾部]，压缩前消息经
 * workspace 侧 log.jsonl（append-only 全量转录）双源合并补全展示。
 * 会话按登录用户隔离（userId 由 Controller 从登录态传入，与 AgentChatService 一致）。
 * 删除仅清 agent 记忆（stateStore.delete）；workspace 侧 jsonl 日志为无害残留，不清理。
 *
 * @author tianbaoxing
 * @date 2026-08-20
 */
@Service
public class AgentSessionService {

    /** 框架 ReActAgent 落盘 state 用的 key（JsonFileAgentStateStore 对应 agent_state.json） */
    static final String STATE_KEY = "agent_state";
    /** 与框架同款 sessionId 合法字符集，先挡路径穿越 */
    private static final Pattern SESSION_ID = Pattern.compile("^[a-zA-Z0-9_\\-.]+$");
    private static final int TITLE_MAX = 30;
    private static final int TOOL_SUMMARY_MAX = 300;
    /** 框架写入 workspace 的 agent 名（AgentScopeConfig 固定 .name("ontology-assistant")） */
    private static final String AGENT_ID = "ontology-assistant";
    /** 框架压缩摘要消息的 name 标记（state 中识别用，SessionDetailService 跳过渲染时复用） */
    static final String COMPACTION_SUMMARY_NAME = "__compaction_summary__";
    /** 摘要消息固定文案开头（转录行无 name 字段，按内容前缀识别链式压缩的历史摘要） */
    private static final String COMPACTION_TEXT_PREFIX = "You are in the middle of a conversation";
    /** 转录行内联工具调用/结果文本格式 */
    private static final Pattern TOOL_CALL_RE =
            Pattern.compile("^\\[tool_call:\\s*([A-Za-z0-9_]+)\\((.*)\\)\\]$");
    private static final Pattern TOOL_RESULT_RE =
            Pattern.compile("^\\[tool_result:\\s*([A-Za-z0-9_]+)\\]\\s*(.*)$", Pattern.DOTALL);
    /** 转录行时间戳（epoch 秒）→ 与 state 一致的本地时间字符串格式 */
    private static final DateTimeFormatter TRANSCRIPT_TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private static final Logger log = LoggerFactory.getLogger(AgentSessionService.class);

    private final ObjectProvider<HarnessAgent> agentProvider;
    private final AgentChatService agentChatService;
    private final AgentRegistry agentRegistry;
    private final ObjectMapper mapper;

    public AgentSessionService(ObjectProvider<HarnessAgent> agentProvider,
            AgentChatService agentChatService, AgentRegistry agentRegistry, ObjectMapper mapper) {
        this.agentProvider = agentProvider;
        this.agentChatService = agentChatService;
        this.agentRegistry = agentRegistry;
        this.mapper = mapper;
    }

    /** 会话列表（updateTime 倒序）；未配置 key 或目录为空均返回空列表 */
    public Map<String, Object> list(String userId) {
        return list(userId, null);
    }

    /** 会话列表（自建智能体路由：agentId 非空走该智能体独立 state 存储） */
    public Map<String, Object> list(String userId, String agentId) {
        AgentStateStore store = storeFor(agentId);
        if (store == null) {
            return Map.of("sessions", List.of());
        }
        List<Map<String, Object>> sessions = new ArrayList<>();
        for (String id : store.listSessionIds(userId)) {
            store.get(userId, id, STATE_KEY, AgentState.class).ifPresent(state -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("sessionId", id);
                m.put("title", effectiveTitle(store, userId, id, state));
                m.put("updateTime", updateTimeOf(state));
                m.put("messageCount", state.getContext() == null ? 0 : state.getContext().size());
                sessions.add(m);
            });
        }
        sessions.sort(Comparator.comparing(
                s -> String.valueOf(s.get("updateTime")), Comparator.reverseOrder()));
        return Map.of("sessions", sessions);
    }

    /** 单会话全部消息：未压缩会话直接渲染 state；被压缩会话双源合并（转录前缀+摘要卡片+保留尾部） */
    public Map<String, Object> messages(String userId, String sessionId) {
        return messages(userId, null, sessionId);
    }

    /** 单会话全部消息（自建智能体路由：P0 不做转录双源合并，state 渲染，压缩历史降级） */
    public Map<String, Object> messages(String userId, String agentId, String sessionId) {
        String id = validate(sessionId);
        AgentStateStore store = requireStoreFor(agentId);
        AgentState state = store.get(userId, id, STATE_KEY, AgentState.class)
                .orElseThrow(() -> new ApiException(404, "会话不存在"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", id);
        result.put("messages", mergedMessages(userId, agentId, id, state));
        return result;
    }

    /** 删除会话：进行中拒绝（409）→ 清 agent 记忆（官方 delete） */
    public Map<String, Object> delete(String userId, String sessionId) {
        return delete(userId, null, sessionId);
    }

    /** 删除会话（自建智能体路由） */
    public Map<String, Object> delete(String userId, String agentId, String sessionId) {
        String id = validate(sessionId);
        AgentStateStore store = requireStoreFor(agentId);
        if (agentChatService.isRunning(id)) {
            throw new ApiException(409, "会话正在处理中，请稍后再删除");
        }
        if (!store.exists(userId, id)) {
            throw new ApiException(404, "会话不存在");
        }
        store.delete(userId, id);
        return Map.of("deleted", id);
    }

    /**
     * 重命名会话标题：校验会话存在（agent_state 落盘过）后写入自定义标题。
     * 标题存独立 key（session_title.json），与 agent_state.json 互不干扰，
     * 流式回答进行中也可安全重命名；删除会话时随会话目录整体清理。
     */
    public Map<String, Object> rename(String userId, String sessionId, String title) {
        return rename(userId, null, sessionId, title);
    }

    /** 重命名会话标题（自建智能体路由） */
    public Map<String, Object> rename(String userId, String agentId, String sessionId, String title) {
        String id = validate(sessionId);
        String t = title == null ? "" : title.strip();
        if (t.isEmpty()) {
            throw new ApiException(400, "标题不能为空");
        }
        if (t.length() > SessionTitleState.TITLE_MAX) {
            throw new ApiException(400, "标题长度不能超过 " + SessionTitleState.TITLE_MAX + " 字");
        }
        AgentStateStore store = requireStoreFor(agentId);
        store.get(userId, id, STATE_KEY, AgentState.class)
                .orElseThrow(() -> new ApiException(404, "会话不存在"));
        store.save(userId, id, SessionTitleState.STORE_KEY, SessionTitleState.of(t));
        return Map.of("sessionId", id, "title", t);
    }

    // ---------------- 内部 ----------------

    /** agentId 非空 → 自建智能体轻量存储；空 → 主智能体（未装配返回 null） */
    private AgentStateStore storeFor(String agentId) {
        if (agentId != null && !agentId.isBlank()) {
            return agentRegistry.stateStoreOf(agentId);
        }
        return storeOrNull();
    }

    /** 同 storeFor，但主智能体未装配时报 503（读操作必须有明确失败语义） */
    private AgentStateStore requireStoreFor(String agentId) {
        if (agentId != null && !agentId.isBlank()) {
            return agentRegistry.stateStoreOf(agentId);
        }
        return requireStore();
    }

    private AgentStateStore storeOrNull() {
        HarnessAgent agent = agentProvider.getIfAvailable();
        return agent == null ? null : agent.getStateStore();
    }

    private AgentStateStore requireStore() {
        AgentStateStore store = storeOrNull();
        if (store == null) {
            throw new ApiException(503, AgentChatService.missingKeyMessage());
        }
        return store;
    }

    private static String validate(String sessionId) {
        if (sessionId == null || !SESSION_ID.matcher(sessionId).matches()) {
            throw new ApiException(400, "非法会话 ID");
        }
        return sessionId;
    }

    /** 会话标题（首条非空 USER 消息截断，跳过压缩摘要；带改写 trace 时取用户原句；驾驶舱统计复用） */
    static String titleOf(AgentState state) {
        if (state.getContext() != null) {
            for (Msg msg : state.getContext()) {
                if (msg.getRole() == MsgRole.USER
                        && !COMPACTION_SUMMARY_NAME.equals(msg.getName())) {
                    RewriteConfigService.RewriteTrace trace = RewriteConfigService.traceOf(msg);
                    String text = (trace != null ? trace.original() : textOf(msg)).strip();
                    if (!text.isEmpty()) {
                        return text.length() > TITLE_MAX ? text.substring(0, TITLE_MAX) + "…" : text;
                    }
                }
            }
        }
        return "(空会话)";
    }

    /** 会话生效标题：手工重命名（session_title）优先，未设置回退派生标题；标题文件异常不阻断、降级派生 */
    static String effectiveTitle(AgentStateStore store, String userId, String sessionId, AgentState state) {
        try {
            SessionTitleState t = store
                    .get(userId, sessionId, SessionTitleState.STORE_KEY, SessionTitleState.class)
                    .orElse(null);
            if (t != null && t.title() != null && !t.title().isBlank()) {
                return t.title();
            }
        } catch (Exception e) {
            // session_title 单文件畸形/读取失败：回退派生标题，不报错
        }
        return titleOf(state);
    }

    private static String updateTimeOf(AgentState state) {
        if (state.getContext() != null) {
            for (int i = state.getContext().size() - 1; i >= 0; i--) {
                String ts = state.getContext().get(i).getTimestamp();
                if (ts != null && !ts.isBlank()) {
                    return ts;
                }
            }
        }
        return "";
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

    /** context → 前端消息结构：user / assistant（thinking、tools、answer、tokens） */
    private List<Map<String, Object>> mapMessages(AgentState state) {
        List<Map<String, Object>> result = new ArrayList<>();
        Map<String, Map<String, Object>> pendingTools = new LinkedHashMap<>();
        if (state.getContext() == null) {
            return result;
        }
        for (Msg msg : state.getContext()) {
            if (COMPACTION_SUMMARY_NAME.equals(msg.getName())) {
                continue; // 压缩摘要由摘要卡片渲染，不再作为 USER 气泡
            }
            if (msg.getRole() == MsgRole.USER) {
                RewriteConfigService.RewriteTrace trace = RewriteConfigService.traceOf(msg);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("role", "user");
                // 带改写 trace 的消息：state 存的是改写后文本，气泡还原用户原句
                m.put("content", trace != null ? trace.original() : textOf(msg));
                m.put("ts", msg.getTimestamp());
                result.add(m);
                if (trace != null) {
                    // 改写过程 → 虚拟 assistant 消息（thinking 展示改写轮，与实时渲染同源同形态）
                    result.add(rewriteCard(trace, msg.getTimestamp()));
                }
            } else if (msg.getRole() == MsgRole.ASSISTANT) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("role", "assistant");
                m.put("ts", msg.getTimestamp());
                StringBuilder thinking = new StringBuilder();
                StringBuilder answer = new StringBuilder();
                List<Map<String, Object>> tools = new ArrayList<>();
                for (ContentBlock b : msg.getContent()) {
                    if (b instanceof ThinkingBlock tb) {
                        thinking.append(tb.getThinking());
                    } else if (b instanceof TextBlock tb) {
                        answer.append(tb.getText());
                    } else if (b instanceof ToolUseBlock tb) {
                        Map<String, Object> t = new LinkedHashMap<>();
                        t.put("name", tb.getName());
                        t.put("input", summarizeObject(tb.getInput()));
                        tools.add(t);
                        pendingTools.put(tb.getId(), t);
                    }
                }
                m.put("thinking", thinking.toString());
                m.put("answer", answer.toString());
                m.put("tools", tools);
                ChatUsage usage = msg.getUsage();
                if (usage != null) {
                    m.put("promptTokens", usage.getInputTokens());
                    m.put("completionTokens", usage.getOutputTokens());
                }
                result.add(m);
            } else if (msg.getRole() == MsgRole.TOOL) {
                // 工具结果按 id 回填到上一条 assistant 消息的 tools 条目
                for (ContentBlock b : msg.getContent()) {
                    if (b instanceof ToolResultBlock tb) {
                        Map<String, Object> t = pendingTools.get(tb.getId());
                        if (t != null) {
                            t.put("output", summarizeOutput(tb.getOutput()));
                        }
                    }
                }
            }
        }
        return result;
    }

    /**
     * 改写轮虚拟消息（assistant 形态）：thinking = traceToText（与实时 thinking 同源）、
     * 无 answer 无工具，tokens 取各步聚合（改写轮成本随轮展示）。压缩历史（log.jsonl 转录）
     * 不含 metadata，改写过程在压缩后记录中缺失，仅保留改写后文本。
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

    // ---------------- 压缩历史双源合并 ----------------

    /**
     * 双源合并渲染：state 被 CompactionMiddleware 压缩后仅含 [摘要+保留尾部]，
     * 压缩前消息只存于 workspace 侧 log.jsonl（append-only 全量转录）。
     * 以保留尾部首条消息 id 为切割线读取转录前缀，拼装 [前缀, 摘要卡片, 尾部]。
     * 未压缩 / 转录不可用 / 切割线找不到 → 摘要卡片 + 纯 state 渲染（降级不报错）。
     * 自建智能体：P0 不做转录合并（转录读取依赖主智能体 workspace 管理器），纯 state 渲染。
     */
    private List<Map<String, Object>> mergedMessages(String userId, String agentId,
            String sessionId, AgentState state) {
        if (agentId != null && !agentId.isBlank()) {
            return mapMessages(state);
        }
        CompactionHistory h = compactionHistory(userId, sessionId, state);
        if (h == null) {
            return mapMessages(state);
        }
        List<Map<String, Object>> merged = new ArrayList<>(h.prefix());
        merged.add(h.card());
        merged.addAll(mapMessages(state));
        return merged;
    }

    /** 压缩历史合并素材：压缩前消息前缀（转录恢复，flat）+ 摘要卡片；未压缩返回 null */
    public record CompactionHistory(List<Map<String, Object>> prefix, Map<String, Object> card) {}

    /**
     * 读取压缩历史素材（state 定位摘要 + 转录读取前缀），供 chat 历史与 session-detail
     * 消息明细共用：调用方自行拼接 [prefix, card, 自身渲染的 state 尾部]。
     */
    public CompactionHistory compactionHistory(String userId, String sessionId, AgentState state) {
        List<Msg> context = state.getContext();
        int summaryIdx = indexOfCompactionSummary(context);
        if (summaryIdx < 0) {
            return null;
        }
        String tailStartId = summaryIdx + 1 < context.size() ? context.get(summaryIdx + 1).getId() : null;
        TranscriptPrefix prefix = transcriptPrefix(userId, sessionId, tailStartId);
        List<Map<String, Object>> prefixMsgs = prefix == null ? List.of() : prefix.messages();
        return new CompactionHistory(prefixMsgs, compactionCard(context.get(summaryIdx),
                prefix == null ? null : prefix.logPath(), prefixMsgs.size()));
    }

    /** 定位最早的压缩摘要消息下标；未压缩返回 -1 */
    private static int indexOfCompactionSummary(List<Msg> context) {
        if (context == null) {
            return -1;
        }
        for (int i = 0; i < context.size(); i++) {
            if (COMPACTION_SUMMARY_NAME.equals(context.get(i).getName())) {
                return i;
            }
        }
        return -1;
    }

    /** 转录前缀读取结果：扁平消息列表 + 转录文件展示路径（供摘要卡片引用） */
    private record TranscriptPrefix(List<Map<String, Object>> messages, String logPath) {}

    /**
     * 读取 log.jsonl 中切割线（保留尾部首条消息 id）之前的消息并扁平映射。
     * 注意：尾部消息在转录中位于摘要行之前，按摘要行切割会与 state 重复，必须按尾部首条 id 切。
     * 文件缺失 / 未出现切割线 / agent 未装配 → null（调用方降级）。
     */
    private TranscriptPrefix transcriptPrefix(String userId, String sessionId, String tailStartId) {
        if (tailStartId == null || tailStartId.isBlank()) {
            return null;
        }
        HarnessAgent agent = agentProvider.getIfAvailable();
        if (agent == null) {
            return null;
        }
        try {
            WorkspaceManager wm = agent.workspaceFor(userId, sessionId);
            RuntimeContext rc = RuntimeContext.builder().userId(userId).sessionId(sessionId).build();
            Path logPath = wm.resolveSessionLogFile(rc, AGENT_ID, sessionId);
            if (logPath == null || !Files.isRegularFile(logPath)) {
                return null;
            }
            List<Map<String, Object>> flat = new ArrayList<>();
            // 归属映射：ASSISTANT 行 id → 自身消息；TOOL 行 id → 解析后的归属 assistant
            // （转录 parentId 为链式：TOOL 行指向前一条 TOOL/ASSISTANT 行，需传递解析）
            Map<String, Map<String, Object>> ownerById = new LinkedHashMap<>();
            for (String line : Files.readAllLines(logPath, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode n;
                try {
                    n = mapper.readTree(line);
                } catch (Exception e) {
                    continue; // 单行畸形跳过，不中断整体加载
                }
                if (!"message".equals(n.path("type").asText())) {
                    continue; // 未知条目类型（compaction/summary 等）跳过
                }
                if (tailStartId.equals(n.path("id").asText(null))) {
                    return new TranscriptPrefix(flat, displayPath(wm, logPath));
                }
                appendFlatEntry(flat, ownerById, n);
            }
            return null; // 转录走完未出现切割线（格式不符）→ 降级
        } catch (Exception e) {
            log.warn("读取会话转录失败 session={}: {}", sessionId, e.getMessage());
            return null;
        }
    }

    /** 单条转录行 → 前端扁平消息（压缩前记录：无 thinking/usage，工具调用按内联文本提取） */
    private static void appendFlatEntry(List<Map<String, Object>> out,
            Map<String, Map<String, Object>> ownerById, JsonNode n) {
        String role = n.path("role").asText("");
        String ts = fmtTranscriptTs(n.path("timestamp"));
        String content = n.path("content").asText("");
        String id = n.path("id").asText(null);
        if ("USER".equals(role)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("role", content.startsWith(COMPACTION_TEXT_PREFIX) ? "compaction" : "user");
            m.put("content", content);
            m.put("ts", ts);
            m.put("flat", true);
            out.add(m);
        } else if ("ASSISTANT".equals(role)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("role", "assistant");
            m.put("ts", ts);
            m.put("flat", true);
            List<Map<String, Object>> tools = new ArrayList<>();
            StringBuilder answer = new StringBuilder();
            for (String seg : content.split("\n", -1)) {
                Matcher mc = TOOL_CALL_RE.matcher(seg.strip());
                if (mc.matches()) {
                    Map<String, Object> t = new LinkedHashMap<>();
                    t.put("name", mc.group(1));
                    t.put("input", truncate(mc.group(2)));
                    tools.add(t);
                } else if (!seg.isBlank()) {
                    answer.append(seg).append('\n');
                }
            }
            m.put("answer", answer.toString().strip());
            m.put("tools", tools);
            out.add(m);
            if (id != null) {
                ownerById.put(id, m);
            }
        } else if ("TOOL".equals(role)) {
            Map<String, Object> owner = ownerById.get(n.path("parentId").asText(null));
            if (owner != null) {
                attachToolResult(owner, content);
                if (id != null) {
                    ownerById.put(id, owner); // 链式 parent：后续 TOOL 行经此映射归到同一 assistant
                }
            }
        }
    }

    /** 工具结果回填到归属 assistant 消息，优先同名工具（兜底首个未回填项） */
    @SuppressWarnings("unchecked")
    private static void attachToolResult(Map<String, Object> target, String content) {
        Matcher mr = TOOL_RESULT_RE.matcher(content.strip());
        String name = mr.matches() ? mr.group(1) : null;
        String output = mr.matches() ? mr.group(2) : content;
        List<Map<String, Object>> tools = (List<Map<String, Object>>) target.get("tools");
        for (Map<String, Object> t : tools) {
            if (t.get("output") == null && (name == null || name.equals(t.get("name")))) {
                t.put("output", truncate(output));
                return;
            }
        }
        for (Map<String, Object> t : tools) {
            if (t.get("output") == null) {
                t.put("output", truncate(output));
                return;
            }
        }
    }

    /** 转录行时间戳（epoch 秒，数值）→ 与 state 一致的本地时间字符串；非数值原样返回 */
    private static String fmtTranscriptTs(JsonNode n) {
        if (!n.isNumber()) {
            String s = n.asText(null);
            return s == null || s.isBlank() ? null : s;
        }
        try {
            return TRANSCRIPT_TS_FMT.format(Instant.ofEpochMilli((long) (n.asDouble() * 1000))
                    .atZone(ZoneId.systemDefault()));
        } catch (Exception e) {
            return null;
        }
    }

    /** 压缩摘要卡片（前端渲染为「记忆压缩」折叠卡片，替代原 USER 气泡） */
    private static Map<String, Object> compactionCard(Msg summary, String logPath, int count) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", "compaction");
        m.put("ts", summary.getTimestamp());
        m.put("content", textOf(summary));
        m.put("logFile", logPath);
        m.put("count", count);
        return m;
    }

    /** 转录文件展示路径：workspace 配置路径 + 相对路径（统一正斜杠）；relativize 失败回退绝对路径 */
    private static String displayPath(WorkspaceManager wm, Path logPath) {
        try {
            Path rel = wm.getWorkspace().toAbsolutePath()
                    .relativize(logPath.toAbsolutePath());
            if (!rel.startsWith("..")) {
                return (wm.getWorkspace() + "/" + rel).replace('\\', '/');
            }
        } catch (Exception ignored) {
            // 回退到绝对路径
        }
        return logPath.toString().replace('\\', '/');
    }

    private String summarizeObject(Object input) {
        if (input == null) {
            return "";
        }
        String s;
        try {
            s = mapper.writeValueAsString(input);
        } catch (Exception e) {
            s = String.valueOf(input);
        }
        return truncate(s);
    }

    private String summarizeOutput(List<ContentBlock> output) {
        if (output == null || output.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ContentBlock b : output) {
            if (b instanceof TextBlock tb) {
                sb.append(tb.getText());
            }
        }
        return truncate(sb.toString());
    }

    private static String truncate(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        return s.length() > TOOL_SUMMARY_MAX ? s.substring(0, TOOL_SUMMARY_MAX) + "…" : s;
    }
}
