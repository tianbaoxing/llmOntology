package com.conca.ontology.agent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.conca.ontology.model.RoleRule;
import com.conca.ontology.service.RolePermissionService;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.tool.ToolResultMessageBuilder;
import io.agentscope.harness.agent.middleware.HarnessRuntimeMiddleware;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 角色权限拦截中间件（安全层兜底，规则来自 RolePermissionService，修改即时生效）：
 *
 * - onActing：按当前用户角色（AgentChatService 经 RuntimeContext 以 CTX_ROLE_KEY 注入）
 *   拦截三类被禁资源——工具名直接匹配、agent_spawn 的 agent_id 参数、
 *   load_skill_through_path 的 skillId 参数。被禁调用不执行，合成 DENIED 工具结果
 *   （写入上下文 + 流式事件，PlanModeMiddleware 同款模式），模型收到后自行换路；
 *   前端 tool 事件照常渲染（result-end 携带 DENIED 状态）。
 * - onSystemPrompt：追加权限提示块，声明被禁工具与子代理，减少模型无效尝试；
 *   技能不在此列——已由 SkillFilter（RuntimeContext 注入，AgentChatService 构建）
 *   从 <available_skills> 清单与 SkillCatalog 中隐藏，模型不可见。
 *
 * order()=2：位于 harness 内置中间件（默认 order=1）外层，权限判定先于内置处理执行。
 * 实现 HarnessRuntimeMiddleware 标记接口：harness 派生子代理时不复制本中间件
 * （子代理在父级 agent_spawn 入口已受控，子代理内部调用不重复判定）。
 *
 * fail-open 说明：无角色信息（ctx 缺 CTX_ROLE_KEY，如框架内部调用）的请求直接放行；
 * 用户聊天链路（AgentChatService.chat）必定注入角色，不受影响。
 */
public class RolePermissionMiddleware implements HarnessRuntimeMiddleware {

    private static final Logger log = LoggerFactory.getLogger(RolePermissionMiddleware.class);

    /** RuntimeContext 中当前用户角色的键（AgentChatService 写入，本中间件读取） */
    public static final String CTX_ROLE_KEY = "userRole";

    private static final String TOOL_AGENT_SPAWN = "agent_spawn";
    private static final String TOOL_LOAD_SKILL = "load_skill_through_path";
    private static final String TOOL_EXECUTE = "execute";

    /** 文件类工具（path 参数为工作区相对路径）：目标在被禁路径树内一律拒绝——
     * 读=泄露、写=篡改、列/搜=信息泄露，封堵"绕过技能入口直接读文件"的泄露链 */
    private static final Set<String> PATH_TOOLS = Set.of(
            "read_file", "write_file", "edit_file", "list_files", "glob_files", "grep_files");

    private final RolePermissionService rolePermissionService;

    public RolePermissionMiddleware(RolePermissionService rolePermissionService) {
        this.rolePermissionService = rolePermissionService;
    }

    @Override
    public int order() {
        // 2 = harness 内置中间件（order=1）外层：权限拦截先于一切内置处理
        return 2;
    }

    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String currentPrompt) {
        RoleRule rule = rolePermissionService.ruleOf(roleOf(ctx));
        if (rule.isEmpty()) {
            return Mono.just(currentPrompt);
        }
        String base = currentPrompt != null ? currentPrompt : "";
        StringBuilder sb = new StringBuilder(base);
        if (!base.isEmpty() && !base.endsWith("\n")) {
            sb.append('\n');
        }
        sb.append("\n<role_permissions>\n")
          .append("当前用户角色无权使用以下资源，调用会被系统拒绝，请勿尝试，")
          .append("直接以现有能力回答或向用户说明无权限：\n");
        if (!rule.deniedTools().isEmpty()) {
            sb.append("- 禁用工具：").append(String.join("、", rule.deniedTools())).append('\n');
        }
        if (!rule.deniedSubagents().isEmpty()) {
            sb.append("- 禁用子代理：").append(String.join("、", rule.deniedSubagents())).append('\n');
        }
        sb.append("同样禁止通过任何文件、搜索或终端工具读取、搜索或间接访问")
          .append("被禁技能与子代理的文件内容，此类调用一律会被系统拒绝。\n");
        sb.append("</role_permissions>");
        return Mono.just(sb.toString());
    }

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        if (input == null || input.toolCalls() == null || input.toolCalls().isEmpty()) {
            return next.apply(input);
        }
        String role = roleOf(ctx);
        RoleRule rule = rolePermissionService.ruleOf(role);
        if (rule.isEmpty()) {
            return next.apply(input);
        }
        List<String> deniedPaths = RolePermissionService.deriveDeniedPaths(rule);

        List<ToolUseBlock> allowed = new ArrayList<>();
        List<ToolUseBlock> denied = new ArrayList<>();
        for (ToolUseBlock call : input.toolCalls()) {
            if (isDenied(rule, deniedPaths, call)) {
                denied.add(call);
            } else {
                allowed.add(call);
            }
        }
        if (denied.isEmpty()) {
            return next.apply(input);
        }
        log.warn("角色权限拦截：user={} role={} denied={}",
                ctx != null ? ctx.getUserId() : null, role,
                denied.stream().map(c -> c.getName() + argBrief(c)).toList());

        AgentState state = RuntimeContext.resolveAgentState(ctx, agent);
        String replyId = state != null ? state.getReplyId() : null;
        String agentName = agent != null && agent.getName() != null ? agent.getName() : "agent";

        // Deferred so the context mutation + events run once, at subscription time.
        Flux<AgentEvent> deniedFlux = Flux.defer(() -> {
            List<AgentEvent> events = new ArrayList<>();
            for (ToolUseBlock call : denied) {
                String message = denyMessage(call);
                ToolResultBlock result = ToolResultBlock.text(message)
                        .withIdAndName(call.getId(), call.getName())
                        .withState(ToolResultState.DENIED);
                if (state != null) {
                    Msg msg = ToolResultMessageBuilder.buildToolResultMsg(result, call, agentName);
                    state.contextMutable().add(msg);
                }
                events.add(new ToolResultStartEvent(replyId, call.getId(), call.getName()));
                events.add(new ToolResultTextDeltaEvent(replyId, call.getId(), call.getName(), message));
                events.add(new ToolResultEndEvent(replyId, call.getId(), call.getName(),
                        ToolResultState.DENIED));
            }
            return Flux.fromIterable(events);
        });

        if (allowed.isEmpty()) {
            return deniedFlux;
        }
        return deniedFlux.concatWith(next.apply(new ActingInput(allowed)));
    }

    /**
     * 五类资源判定（全部纯内存匹配，规则在 onActing 入口取一次）：
     * 工具名；agent_spawn 的 agent_id；load_skill_through_path 的 skillId；
     * 文件类工具的 path 落在被禁路径树内；execute 的 command/working_directory
     * 含被禁路径片段（终端读文件的兜底）。
     */
    private static boolean isDenied(RoleRule rule, List<String> deniedPaths, ToolUseBlock call) {
        String name = call.getName();
        if (name == null) {
            return false;
        }
        if (rule.deniedTools().contains(name)) {
            return true;
        }
        if (TOOL_AGENT_SPAWN.equals(name)) {
            return rule.deniedSubagents().contains(stringArg(call, "agent_id"));
        }
        if (TOOL_LOAD_SKILL.equals(name)) {
            return RolePermissionService.matchesDeniedSkill(rule.deniedSkills(), stringArg(call, "skillId"));
        }
        if (PATH_TOOLS.contains(name)) {
            return inDeniedTree(normalizePath(stringArg(call, "path")), deniedPaths);
        }
        if (TOOL_EXECUTE.equals(name)) {
            return executeTouchesDenied(call, deniedPaths);
        }
        return false;
    }

    private static String denyMessage(ToolUseBlock call) {
        String name = call.getName();
        if (TOOL_AGENT_SPAWN.equals(name)) {
            return "权限不足：当前用户角色无权调用子代理 " + stringArg(call, "agent_id")
                    + "，请改用其他方式完成任务。";
        }
        if (TOOL_LOAD_SKILL.equals(name)) {
            return "权限不足：当前用户角色无权使用技能 " + stringArg(call, "skillId")
                    + "，请勿再次尝试。";
        }
        if (TOOL_EXECUTE.equals(name)) {
            return "权限不足：当前用户角色无权访问被禁技能/子代理的文件，该命令被拒绝。";
        }
        if (PATH_TOOLS.contains(name)) {
            return "权限不足：当前用户角色无权访问被禁技能/子代理的文件（"
                    + stringArg(call, "path") + "），请勿通过文件工具绕过权限。";
        }
        return "权限不足：当前用户角色无权使用工具 " + name
                + "，请勿再次尝试，直接以文本回答用户。";
    }

    /**
     * 工作区相对路径归一化（与 Windows 文件系统大小写语义对齐，防绕过变体）：
     * trim、\→/、统一小写、去 ./ 与前导 /、. 与 .. 段逻辑消解；
     * 根部的 .. 段直接丢弃（clamp 到工作区根，防 ../ 前缀逃逸匹配）。
     */
    private static String normalizePath(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String p = raw.trim().replace('\\', '/').toLowerCase();
        Deque<String> stack = new ArrayDeque<>();
        for (String seg : p.split("/")) {
            if (seg.isEmpty() || seg.equals(".")) {
                continue;
            }
            if (seg.equals("..")) {
                if (!stack.isEmpty()) {
                    stack.pop();
                }
                continue;
            }
            stack.push(seg);
        }
        StringBuilder sb = new StringBuilder();
        for (Iterator<String> it = stack.descendingIterator(); it.hasNext(); ) {
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(it.next());
        }
        return sb.toString();
    }

    /** 目标路径是否落在被禁路径树内：等于被禁目录/文件，或位于其下（denied/… 前缀） */
    private static boolean inDeniedTree(String path, List<String> deniedPaths) {
        if (path.isEmpty() || deniedPaths.isEmpty()) {
            return false;
        }
        for (String denied : deniedPaths) {
            if (path.equals(denied) || path.startsWith(denied + "/")) {
                return true;
            }
        }
        return false;
    }

    /** execute 兜底：command / working_directory 归一化后包含任一被禁路径片段即拒绝 */
    private static boolean executeTouchesDenied(ToolUseBlock call, List<String> deniedPaths) {
        for (String key : List.of("command", "working_directory")) {
            String raw = stringArg(call, key);
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String normalized = raw.replace('\\', '/').toLowerCase();
            for (String denied : deniedPaths) {
                if (normalized.contains(denied)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 从工具入参取字符串值（缺参返回 null，不参与匹配） */
    private static String stringArg(ToolUseBlock call, String key) {
        Map<String, Object> args = call.getInput();
        Object v = args == null ? null : args.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static String argBrief(ToolUseBlock call) {
        if (TOOL_AGENT_SPAWN.equals(call.getName())) {
            return "(" + stringArg(call, "agent_id") + ")";
        }
        if (TOOL_LOAD_SKILL.equals(call.getName())) {
            return "(" + stringArg(call, "skillId") + ")";
        }
        return "";
    }

    private static String roleOf(RuntimeContext ctx) {
        if (ctx == null) {
            return null;
        }
        Object role = ctx.get(CTX_ROLE_KEY);
        return role instanceof String s ? s : null;
    }
}
