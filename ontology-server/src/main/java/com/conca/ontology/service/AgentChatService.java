package com.conca.ontology.service;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.conca.ontology.agent.RolePermissionMiddleware;
import com.conca.ontology.config.AgentScopeConfig;
import com.conca.ontology.model.RewriteConfigFile;
import com.conca.ontology.model.UserEntry;
import com.conca.ontology.service.RewriteConfigService.PipelineStep;
import com.conca.ontology.service.RewriteConfigService.RewriteTrace;
import com.conca.ontology.service.RewriteConfigService.StepResult;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.SkillFilter;
import io.agentscope.core.skill.repository.AgentSkillRepository;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultDataDeltaEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.harness.agent.HarnessAgent;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * AI 问答服务：把 HarnessAgent 的 AgentEvent 流映射为 SSE 事件推给页面。
 * 事件契约（前端 chat.html 同步实现）：
 * session → 会话号；round → 轮次边界（ReAct 每轮模型调用一对
 * start/end，end 携带该轮 tokens；前端按轮分块渲染，与历史渲染一致）；
 * thinking → 思考增量；tool → 工具调用与执行
 * （phase=start/args/end 为调用阶段，args 为参数增量；
 * phase=result-start/result-delta/result-end 为执行结果阶段，
 * result-delta 为结果文本增量，result-end 携带 state（success/error 等），
 * id 全程用于配对同一次调用）；
 * answer → 正文增量；done → 结束；error → 错误。
 *
 * 语义改写：启用后主 agent 启动前先执行「改写轮」（独立 rewrite agent 管道），
 * 以同样的 round/thinking 事件把改写过程模拟为思考过程推送（前端零改动）；
 * 改写后文本替换用户消息送入 ReAct，trace 存 USER 消息 metadata 供历史回放。
 */
@Service
public class AgentChatService {

    private static final Logger log = LoggerFactory.getLogger(AgentChatService.class);
    private static final long SSE_TIMEOUT_MS = 180_000L;
    private static final long HEARTBEAT_SECONDS = 15;

    private final ObjectProvider<HarnessAgent> agentProvider;
    private final Set<String> runningSessions = ConcurrentHashMap.newKeySet();

    /** 进行中会话的所属用户（sessionId → userId），供停止接口校验归属并作用域中断 */
    private final Map<String, String> sessionOwners = new ConcurrentHashMap<>();

    /** 反射缓存：HarnessAgent 持有的底层 ReActAgent（interrupt 需按其会话作用域触发） */
    private volatile ReActAgent delegateRef;

    private final ContentSecurityService contentSecurityService;
    private final RewriteConfigService rewriteConfigService;
    private final RolePermissionService rolePermissionService;
    private final UserService userService;

    public AgentChatService(ObjectProvider<HarnessAgent> agentProvider,
            ContentSecurityService contentSecurityService, RewriteConfigService rewriteConfigService,
            RolePermissionService rolePermissionService, UserService userService) {
        this.agentProvider = agentProvider;
        this.contentSecurityService = contentSecurityService;
        this.rewriteConfigService = rewriteConfigService;
        this.rolePermissionService = rolePermissionService;
        this.userService = userService;
    }

    /** agent 是否已装配（OPENROUTER_API_KEY 已配置） */
    public boolean isAvailable() {
        return agentProvider.getIfAvailable() != null;
    }

    /** 会话是否有流式请求进行中（供历史会话删除前互斥检查） */
    public boolean isRunning(String sessionId) {
        return sessionId != null && runningSessions.contains(sessionId);
    }

    /**
     * 优雅停止某会话进行中的回答：向框架触发该会话作用域的 USER 中断（而非拆断连接），
     * 框架在 handleInterrupt 时调用 saveStateToSession，把「用户问题 + 已生成的部分回答」落盘，
     * 随后该轮流正常收尾（因此刷新后问题不再消失）。
     * 仅允许该会话的发起用户操作；无进行中的回答返回 false。
     */
    public boolean stop(String sessionId, String userId) {
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        // 非当前用户发起的会话或已无进行中的流 → 拒绝
        String owner = sessionOwners.get(sessionId);
        if (owner == null || !owner.equals(userId)) {
            return false;
        }
        if (!runningSessions.contains(sessionId)) {
            return false;
        }
        ReActAgent delegate = delegate();
        if (delegate == null) {
            log.warn("无法获取底层 ReActAgent，停止功能不可用（session={}）", sessionId);
            return false;
        }
        // 作用域到 (userId, sessionId) 的 USER 源中断；当前进行中的流观察到后走 handleInterrupt → 落盘
        delegate.interrupt(userId, sessionId, null);
        return true;
    }

    /** 反射读取 HarnessAgent 持有的底层 ReActAgent（结果缓存，agent 为单例） */
    private ReActAgent delegate() {
        ReActAgent d = delegateRef;
        if (d == null) {
            synchronized (this) {
                d = delegateRef;
                if (d == null) {
                    HarnessAgent agent = agentProvider.getIfAvailable();
                    if (agent == null) {
                        return null;
                    }
                    try {
                        Field f = HarnessAgent.class.getDeclaredField("delegate");
                        f.setAccessible(true);
                        d = (ReActAgent) f.get(agent);
                        delegateRef = d;
                    } catch (Exception e) {
                        log.warn("反射读取 HarnessAgent.delegate 失败：{}", e.getMessage());
                        return null;
                    }
                }
            }
        }
        return d;
    }

    /** 对外提示未配置 key 时的错误文案 */
    public static String missingKeyMessage() {
        return "未配置 OPENROUTER_API_KEY：请在项目根目录 .env 中填写后重启服务（模板见 .env.example）。";
    }

    /**
     * 工具清单：从 HarnessAgent 的 Toolkit 实时读取
     * （页面展示的工具 = 模型本次实际可见的工具集，单一事实来源；
     * 记忆工具开关切换后 Toolkit 增减，本清单随之变化）。
     */
    public List<Map<String, String>> listTools() {
        List<Map<String, String>> tools = new ArrayList<>();
        HarnessAgent agent = agentProvider.getIfAvailable();
        if (agent == null) {
            return tools;
        }
        List<String> names = new ArrayList<>(agent.getToolkit().getToolNames());
        Collections.sort(names);
        for (String name : names) {
            AgentTool tool = agent.getToolkit().getTool(name);
            if (tool == null) {
                continue;
            }
            String desc = tool.getDescription();
            String type = "";
            if (tool instanceof ToolBase toolBase) {
                if (toolBase.isMcp()) {
                    type = "mcp";
                } else {
                    type = isFrameworkTool(tool) ? "内置工具" : "自定义工具";
                }
            }

            tools.add(Map.of(
                    "name", name,
                    "description", desc == null ? "" : desc,
                    "type",type));
        }
        return tools;
    }

    /** 被包装的工具对象是否来自框架自身包（io.agentscope.*） */
    private static boolean isFrameworkTool(AgentTool tool) {
        try {
            java.lang.reflect.Method m = tool.getClass().getDeclaredMethod("getToolObject");
            m.setAccessible(true); // 包私有方法，跨包反射必须显式开放
            Object target = m.invoke(tool);
            return target == null || target.getClass().getName().startsWith("io.agentscope.");
        } catch (NoSuchMethodException e) {
            // 非 ReflectiveFunctionTool 包装（直接继承 ToolBase 的类），按自身类名判断
            return tool.getClass().getName().startsWith("io.agentscope.");
        } catch (Exception e) {
            // 反射失败时保守视为框架工具，避免误标自定义
            return true;
        }
    }

    /**
     * 技能清单：从 HarnessAgent 已装配的技能仓库读取
     * （页面展示的技能 = 模型实际可用的技能，单一事实来源）。
     * 跨仓库按 name 去重（workspace skills 与全局目录可能同名，取先注册的）。
     */
    public List<Map<String, String>> listSkills() {
        List<Map<String, String>> skills = new ArrayList<>();
        HarnessAgent agent = agentProvider.getIfAvailable();
        if (agent == null) {
            return skills;
        }
        Set<String> seen = new HashSet<>();
        for (AgentSkillRepository repo : agent.getSkillRepositories()) {
            for (AgentSkill skill : repo.getAllSkills()) {
                if (skill == null || skill.getName() == null || !seen.add(skill.getName())) {
                    continue;
                }
                String desc = skill.getDescription();
                skills.add(Map.of(
                        "name", skill.getName(),
                        "description", desc == null ? "" : desc));
            }
        }
        return skills;
    }

    /**
     * 子 Agent 清单：从 HarnessAgent 的 SubagentManager 注册表实时读取，
     * 仅读已注册的子 agent 模板（不实例化）。chat 页并入技能行做展示。
     */
    public List<Map<String, String>> listSubagents() {
        List<Map<String, String>> subs = new ArrayList<>();
        HarnessAgent agent = agentProvider.getIfAvailable();
        if (agent == null) {
            return subs;
        }
        var manager = agent.getSubagentAgentManager();
        if (manager == null) {
            return subs;
        }
        List<String> ids = new ArrayList<>(manager.getAgentFactories().keySet());
        Collections.sort(ids);
        for (String id : ids) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("agentId", id);
            manager.getDeclaration(id).ifPresent(d -> {
                if (d.getDescription() != null) m.put("description", d.getDescription());
                if (d.getModel() != null) m.put("model", d.getModel());
                if (d.getMode() != null) m.put("mode", d.getMode().name());
            });
            subs.add(m);
        }
        return subs;
    }

    // ---------------- 清单按角色过滤（体验层；执行层拦截见 RolePermissionMiddleware） ----------------

    /** 工具清单（剔除该角色被禁工具；ADMIN/无角色返回全集） */
    public List<Map<String, String>> listTools(String role) {
        List<String> denied = rolePermissionService.ruleOf(role).deniedTools();
        if (denied.isEmpty()) {
            return listTools();
        }
        return listTools().stream()
                .filter(t -> !denied.contains(t.get("name")))
                .toList();
    }

    /** 技能清单（剔除该角色被禁技能；ADMIN/无角色返回全集） */
    public List<Map<String, String>> listSkills(String role) {
        List<String> denied = rolePermissionService.ruleOf(role).deniedSkills();
        if (denied.isEmpty()) {
            return listSkills();
        }
        return listSkills().stream()
                .filter(s -> !denied.contains(s.get("name")))
                .toList();
    }

    /** 子 Agent 清单（剔除该角色被禁子代理；ADMIN/无角色返回全集） */
    public List<Map<String, String>> listSubagents(String role) {
        List<String> denied = rolePermissionService.ruleOf(role).deniedSubagents();
        if (denied.isEmpty()) {
            return listSubagents();
        }
        return listSubagents().stream()
                .filter(s -> !denied.contains(s.get("agentId")))
                .toList();
    }


    /**
     * 流式问答：订阅 streamEvents 并逐事件转发到 SseEmitter。
     * userId 为当前登录用户（会话/记忆按用户隔离，落盘在 workspace/{userId}/ 下）。
     */
    public SseEmitter chat(String sessionId, String message, String userId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        String session = (sessionId == null || sessionId.isBlank()) ? UUID.randomUUID().toString() : sessionId;

        HarnessAgent agent = agentProvider.getIfAvailable();
        if (agent == null) {
            send(emitter, "error", Map.of("message", missingKeyMessage()));
            emitter.complete();
            return emitter;
        }
        if (!runningSessions.add(session)) {
            send(emitter, "error", Map.of("message", "上一条消息还在处理中，请稍候。"));
            emitter.complete();
            return emitter;
        }
        sessionOwners.put(session, userId);

        send(emitter, "session", Map.of("sessionId", session));

        ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "agent-sse-heartbeat");
            t.setDaemon(true);
            return t;
        });
        heartbeat.scheduleAtFixedRate(() -> {
            try {
                emitter.send(SseEmitter.event().comment("ping"));
            } catch (Exception ignored) {
                // 连接已断开，忽略
            }
        }, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);

        emitter.onTimeout(emitter::complete);
        emitter.onCompletion(() -> {
            runningSessions.remove(session);
            sessionOwners.remove(session);
            heartbeat.shutdownNow();
        });

        RuntimeContext ctx = buildContext(userId, session);

        // 语义改写轮在主 agent 之前执行（LLM 阻塞调用 → boundedElastic 线程池，不占 servlet 线程）：
        // 过程以 round/thinking 模拟思考事件实时推送，改写后文本替换用户消息送入 ReAct；
        // 完整 trace 存 USER 消息 metadata（刷新后 AgentSessionService 还原原句并渲染改写卡片）
        Disposable disposable = Mono.fromCallable(() -> prepareUserMsg(emitter, session, userId, message))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(userMsg -> agent.streamEvents(userMsg, ctx))
                .doFinally(signal -> {
                    runningSessions.remove(session);
                    sessionOwners.remove(session);
                    heartbeat.shutdownNow();
                    emitter.complete();
                })
                .subscribe(
                        event -> handleEvent(emitter, event),
                        error -> {
                            log.error("agent 事件流异常", error);
                            send(emitter, "error", Map.of("message", safeMessage(error)));
                        });
        emitter.onError(t -> disposable.dispose());
        return emitter;
    }

    // ---------------- 角色权限上下文 ----------------

    /**
     * 构建本次调用的 RuntimeContext，并注入角色权限信息：
     * - userRole（CTX_ROLE_KEY）：RolePermissionMiddleware 判定 onActing 拦截与
     *   onSystemPrompt 提示的依据（聊天开始时的角色快照，免中途查库）；
     * - SkillFilter（被禁技能非空时）：框架 HarnessSkillMiddleware 原生支持，
     *   从 <available_skills> 清单与 SkillCatalog 中隐藏被禁技能（模型不可见）。
     */
    private RuntimeContext buildContext(String userId, String session) {
        RuntimeContext.Builder builder = RuntimeContext.builder()
                .userId(userId)
                .sessionId(session);
        String role = roleOf(userId);
        builder.put(RolePermissionMiddleware.CTX_ROLE_KEY, role);
        List<String> deniedSkills = rolePermissionService.ruleOf(role).deniedSkills();
        if (!deniedSkills.isEmpty()) {
            builder.put(SkillFilter.class,
                    SkillFilter.disable(deniedSkills.toArray(String[]::new)));
        }
        return builder.build();
    }

    /** 当前用户角色；查不到（防御，拦截器已保证登录）按 USER 最小权限 */
    private String roleOf(String userId) {
        UserEntry u = userService.findByUserId(userId);
        return u != null ? u.role() : UserEntry.ROLE_USER;
    }

    // ---------------- 聊天前置改写编排 ----------------

    /**
     * 构建送入主 agent 的用户消息：
     * - 禁用词命中 → 返回原文（由 ContentSecurityMiddleware 拦截提示并落盘，省改写调用）；
     * - 未启用任何改写功能 → 返回原文（零开销，行为与无改写配置完全一致）；
     * - 启用 → 独立 rewrite agent 按管道执行（过程实时推 thinking），改写后文本入 ReAct，
     *   trace 存消息 metadata；任何编排异常降级用原文，绝不阻断聊天。
     */
    private Msg prepareUserMsg(SseEmitter emitter, String session, String userId, String message) {
        Msg original = Msg.builder().role(MsgRole.USER).textContent(message).build();
        try {
            if (!contentSecurityService.forbiddenWordCheck(message).isEmpty()) {
                return original;
            }
            RewriteConfigFile cfg = rewriteConfigService.loadAll();
            boolean anyEnabled = cfg.features().values().stream().anyMatch(RewriteConfigFile.Feature::enabled);
            if (!anyEnabled) {
                return original;
            }
            return rewriteWithThinking(emitter, session, userId, cfg, message);
        } catch (Exception e) {
            log.warn("聊天改写编排异常，使用原文继续：{}", e.getMessage());
            return original;
        }
    }

    /**
     * 执行改写管道并把过程推给前端（改写虚拟轮）：
     * round start → thinking「原句」→ 每步完成即推该步行（skipped 步瞬时推）→
     * thinking「送入 AI：最终文本」→ round end（携带该轮聚合 tokens）。
     * 展示行与历史回放共用 RewriteConfigService.stepLine（刷新前后形态一致）。
     */
    private Msg rewriteWithThinking(SseEmitter emitter, String session, String userId,
            RewriteConfigFile cfg, String message) {
        List<String> history = historyForRewrite(userId, session, cfg);
        String replyId = "rewrite-" + UUID.randomUUID().toString().substring(0, 8);
        long inTokens = 0;
        long outTokens = 0;
        String current = message;
        List<PipelineStep> steps = new ArrayList<>();

        send(emitter, "round", Map.of("phase", "start", "replyId", replyId));
        sendThinking(emitter, "原句：" + message + "\n");
        for (String type : RewriteConfigService.PIPELINE_ORDER) {
            RewriteConfigFile.Feature f = cfg.features().get(type);
            PipelineStep step;
            if (f == null || !f.enabled()) {
                step = new PipelineStep(type, RewriteConfigService.STATUS_SKIPPED, null, null, null, 0, 0);
            } else {
                try {
                    StepResult r = rewriteConfigService.executeStep(cfg.version(), type, f, current, history);
                    step = r.step();
                    inTokens += r.promptTokens();
                    outTokens += r.completionTokens();
                    if (step.rewrittenText() != null && !step.rewrittenText().isBlank()) {
                        current = step.rewrittenText();
                    }
                } catch (Exception e) {
                    log.warn("聊天改写步骤 [{}] 失败，沿用当前文本：{}", type, e.getMessage());
                    step = new PipelineStep(type, RewriteConfigService.STATUS_ERROR, null, null,
                            e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), 0, 0);
                }
            }
            steps.add(step);
            sendThinking(emitter, RewriteConfigService.stepLine(step) + "\n");
        }
        sendThinking(emitter, "送入 AI：" + current);
        send(emitter, "round", Map.of(
                "phase", "end", "replyId", replyId,
                "promptTokens", inTokens, "completionTokens", outTokens));

        String applied = steps.stream()
                .filter(s -> RewriteConfigService.STATUS_APPLIED.equals(s.status()))
                .map(PipelineStep::type)
                .reduce((a, b) -> a + "," + b)
                .orElse("");
        if (!applied.isEmpty()) {
            log.info("聊天语义改写生效（功能=[{}]）：{} → {}", applied, brief(message), brief(current));
        }
        String traceJson = RewriteConfigService.traceJson(new RewriteTrace(message, current, steps));
        if (traceJson == null) {
            return Msg.builder().role(MsgRole.USER).textContent(current).build();
        }
        return Msg.builder()
                .role(MsgRole.USER)
                .textContent(current)
                .metadata(Map.of(RewriteConfigService.META_REWRITE_TRACE, traceJson))
                .build();
    }

    /** 上下文改写启用时读取会话最近历史（USER 行还原改写前原句，保持真实对话） */
    private List<String> historyForRewrite(String userId, String session, RewriteConfigFile cfg) {
        RewriteConfigFile.Feature f = cfg.features().get(RewriteConfigService.TYPE_CONTEXT);
        if (f == null || !f.enabled()) {
            return List.of();
        }
        try {
            ReActAgent delegate = delegate();
            if (delegate == null) {
                return List.of();
            }
            AgentState state = delegate.getAgentState(userId, session);
            List<Msg> context = state.getContext();
            List<String> lines = new ArrayList<>();
            for (int i = context.size() - 1; i >= 0 && lines.size() < 10; i--) {
                Msg m = context.get(i);
                String role = m.getRole() == MsgRole.USER ? "用户"
                        : m.getRole() == MsgRole.ASSISTANT ? "助手" : null;
                if (role == null) {
                    continue;
                }
                String text = textOf(m);
                if (MsgRole.USER == m.getRole()) {
                    RewriteTrace trace = RewriteConfigService.traceOf(m);
                    if (trace != null && trace.original() != null) {
                        text = trace.original();
                    }
                }
                if (text != null && !text.isBlank()) {
                    lines.add(0, role + "：" + text.trim());
                }
            }
            return lines;
        } catch (Exception e) {
            log.warn("读取会话历史失败（上下文改写将缺少历史）：{}", e.getMessage());
            return List.of();
        }
    }

    private void sendThinking(SseEmitter emitter, String delta) {
        send(emitter, "thinking", Map.of("delta", delta));
    }

    private static String textOf(Msg msg) {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock b : msg.getContent()) {
            if (b instanceof TextBlock tb) {
                sb.append(tb.getText());
            }
        }
        return sb.toString().trim();
    }

    private static String brief(String s) {
        return s.length() <= 80 ? s : s.substring(0, 80) + "…";
    }

    private void handleEvent(SseEmitter emitter, AgentEvent event) {
        switch (event.getType()) {
            case MODEL_CALL_START -> send(emitter, "round", Map.of(
                    "phase", "start", "replyId", safeStr(((ModelCallStartEvent) event).getReplyId())));
            case MODEL_CALL_END -> send(emitter, "round", roundEndPayload((ModelCallEndEvent) event));
            case THINKING_BLOCK_DELTA -> send(emitter, "thinking",
                    Map.of("delta", ((ThinkingBlockDeltaEvent) event).getDelta()));
            case TEXT_BLOCK_DELTA -> send(emitter, "answer",
                    Map.of("delta", ((TextBlockDeltaEvent) event).getDelta()));
            case TOOL_CALL_START -> send(emitter, "tool", Map.of(
                    "phase", "start", "id", ((ToolCallStartEvent) event).getToolCallId(),
                    "name", ((ToolCallStartEvent) event).getToolCallName()));
            case TOOL_CALL_DELTA -> send(emitter, "tool", Map.of(
                    "phase", "args", "id", ((ToolCallDeltaEvent) event).getToolCallId(),
                    "delta", safeDelta(((ToolCallDeltaEvent) event).getDelta())));
            case TOOL_CALL_END -> send(emitter, "tool", Map.of(
                    "phase", "end", "id", ((ToolCallEndEvent) event).getToolCallId(),
                    "name", ((ToolCallEndEvent) event).getToolCallName()));
            case TOOL_RESULT_START -> send(emitter, "tool", Map.of(
                    "phase", "result-start", "id", ((ToolResultStartEvent) event).getToolCallId(),
                    "name", ((ToolResultStartEvent) event).getToolCallName()));
            case TOOL_RESULT_TEXT_DELTA -> send(emitter, "tool", Map.of(
                    "phase", "result-delta", "id", ((ToolResultTextDeltaEvent) event).getToolCallId(),
                    "delta", safeDelta(((ToolResultTextDeltaEvent) event).getDelta())));
            case TOOL_RESULT_DATA_DELTA -> send(emitter, "tool", Map.of(
                    "phase", "result-delta", "id", ((ToolResultDataDeltaEvent) event).getToolCallId(),
                    "delta", safeDelta(dataDeltaText(((ToolResultDataDeltaEvent) event).getData()))));
            case TOOL_RESULT_END -> send(emitter, "tool", Map.of(
                    "phase", "result-end", "id", ((ToolResultEndEvent) event).getToolCallId(),
                    "name", ((ToolResultEndEvent) event).getToolCallName(),
                    "state", safeState(((ToolResultEndEvent) event).getState())));
            case AGENT_RESULT -> send(emitter, "done", resultPayload((AgentResultEvent) event));
            default -> { /* 其余事件不透出，保持前端契约精简 */ }
        }
    }

    private Map<String, Object> resultPayload(AgentResultEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        Msg result = event.getResult();
        if (result != null) {
            if (result.getUsage() != null) {
                payload.put("promptTokens", result.getUsage().getInputTokens());
                payload.put("completionTokens", result.getUsage().getOutputTokens());
            }
        }
        return payload;
    }

    /** 轮次结束：携带该轮模型调用的 tokens（usage 可能缺失，如异常截断） */
    private static Map<String, Object> roundEndPayload(ModelCallEndEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("phase", "end");
        payload.put("replyId", safeStr(event.getReplyId()));
        ChatUsage usage = event.getUsage();
        if (usage != null) {
            payload.put("promptTokens", usage.getInputTokens());
            payload.put("completionTokens", usage.getOutputTokens());
        }
        return payload;
    }

    private void send(SseEmitter emitter, String name, Map<String, Object> data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data));
        } catch (Exception e) {
            log.debug("SSE 发送失败（连接可能已断开）：{}", e.getMessage());
        }
    }

    private String safeMessage(Throwable error) {
        String msg = error.getMessage();
        return (msg == null || msg.isBlank()) ? error.getClass().getSimpleName() : msg;
    }

    /** Map.of 不接受 null 值，delta 可能为 null 时兜底空串 */
    private static String safeDelta(String delta) {
        return delta == null ? "" : delta;
    }

    private static String safeStr(String s) {
        return s == null ? "" : s;
    }

    /** DataDelta 事件携带结构化 block：文本直接透出，其余以类型名占位 */
    private static String dataDeltaText(ContentBlock data) {
        if (data instanceof TextBlock tb) {
            return tb.getText() == null ? "" : tb.getText();
        }
        return data == null ? "" : "[" + data.getClass().getSimpleName() + "]";
    }

    private static String safeState(ToolResultState state) {
        return state == null ? "" : state.name();
    }
}
