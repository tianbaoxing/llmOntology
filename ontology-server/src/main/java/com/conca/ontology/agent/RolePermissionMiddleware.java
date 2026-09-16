package com.conca.ontology.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

        List<ToolUseBlock> allowed = new ArrayList<>();
        List<ToolUseBlock> denied = new ArrayList<>();
        for (ToolUseBlock call : input.toolCalls()) {
            if (isDenied(rule, role, call)) {
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

    /** 三类资源判定：工具名直接匹配；agent_spawn 看 agent_id；load_skill_through_path 看 skillId */
    private boolean isDenied(RoleRule rule, String role, ToolUseBlock call) {
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
            return rolePermissionService.isDeniedSkillId(role, stringArg(call, "skillId"));
        }

        if (TOOL_LOAD_SKILL.equals(name)) {
            return rolePermissionService.isDeniedSkillId(role, stringArg(call, "skillid"));
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
        return "权限不足：当前用户角色无权使用工具 " + name
                + "，请勿再次尝试，直接以文本回答用户。";
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
