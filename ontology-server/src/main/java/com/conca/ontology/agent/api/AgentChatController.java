package com.conca.ontology.agent.api;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.conca.ontology.user.web.SessionUser;
import com.conca.ontology.builder.service.AgentBuilderService;
import com.conca.ontology.agent.dto.request.AgentChatRequest;
import com.conca.ontology.agent.dto.request.SessionTitleRequest;
import com.conca.ontology.shared.web.ApiResponse;
import com.conca.ontology.shared.web.ApiException;
import com.conca.ontology.user.model.UserEntry;
import com.conca.ontology.agent.service.AgentChatService;
import com.conca.ontology.agent.service.AgentSessionService;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;

/**
 * AI 问答入口（SSE 流式）+ 历史会话管理：
 * POST /api/agent/sessions/{id}/messages {message} → text/event-stream（在会话下创建消息）。
 * 事件序列：session → round ×N（每轮模型调用 start/end，end 携带该轮 tokens）
 * → thinking / tool / answer → done 或 error。
 * GET  /api/agent/sessions                 → 会话列表（updateTime 倒序）
 * GET  /api/agent/sessions/{id}/messages   → 单会话消息
 * PUT  /api/agent/sessions/{id}/title      → 重命名会话标题（session_title 独立落盘）
 * DELETE /api/agent/sessions/{id}          → 删除会话（含 AI 记忆）
 * GET  /api/agent/skills                   → 技能清单（chat 页输入区快捷插入）
 * GET  /api/agent/tools                    → 工具清单（chat 页「工具列表」悬停面板）
 * 会话/记忆按登录用户隔离：userId 一律取当前会话，不信任请求参数。
 */
@RestController
@RequestMapping("/api/agent")
@Validated
public class AgentChatController {

    private final AgentChatService agentChatService;
    private final AgentSessionService agentSessionService;
    private final AgentBuilderService agentBuilderService;

    public AgentChatController(AgentChatService agentChatService,
            AgentSessionService agentSessionService, AgentBuilderService agentBuilderService) {
        this.agentChatService = agentChatService;
        this.agentSessionService = agentSessionService;
        this.agentBuilderService = agentBuilderService;
    }

    /** 技能清单（按当前用户角色过滤被禁技能）；agent 未装配（未配置 key）时为空列表 */
    @GetMapping("/skills")
    public ApiResponse<Map<String, Object>> skills(HttpSession session) {
        return ApiResponse.ok(Map.of(
                "agentAvailable", agentChatService.isAvailable(),
                "skills", agentChatService.listSkills(currentRole(session))));
    }

    /** 工具清单（按当前用户角色过滤被禁工具）；Toolkit 实时存活集，agent 未装配时为空列表 */
    @GetMapping("/tools")
    public ApiResponse<Map<String, Object>> tools(HttpSession session) {
        return ApiResponse.ok(Map.of(
                "agentAvailable", agentChatService.isAvailable(),
                "tools", agentChatService.listTools(currentRole(session))));
    }

    /** 子 Agent 清单（按当前用户角色过滤被禁子代理；注册表模板数，不实例化） */
    @GetMapping("/subagents")
    public ApiResponse<Map<String, Object>> subagents(HttpSession session) {
        return ApiResponse.ok(Map.of(
                "agentAvailable", agentChatService.isAvailable(),
                "subagents", agentChatService.listSubagents(currentRole(session))));
    }

    /** 流式问答：agentId 留空 = 主智能体；非空 = 自建智能体（active 所有人，test 仅管理员试运行） */
    @PostMapping(value = "/sessions/{id}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@PathVariable String id, @Valid @RequestBody AgentChatRequest request,
            HttpSession session) {
        SessionUser user = SessionUser.of(session);
        if (user == null) {
            throw new ApiException(401, "未登录或会话已过期");
        }
        String agentId = request.agentId();
        if (agentId != null && !agentId.isBlank()) {
            agentBuilderService.requireChatAllowed(agentId, user.isAdmin());
        }
        return agentChatService.chat(id, request.message(), user.userId(), agentId);
    }

    /** 会话列表（?agentId= 自建智能体会话；缺省主智能体） */
    @GetMapping("/sessions")
    public ApiResponse<Map<String, Object>> sessions(HttpSession session,
            @RequestParam(required = false) String agentId) {
        return ApiResponse.ok(agentSessionService.list(currentUserId(session), agentId));
    }

    /** 单会话消息（?agentId= 自建智能体会话） */
    @GetMapping("/sessions/{id}/messages")
    public ApiResponse<Map<String, Object>> messages(@PathVariable String id, HttpSession session,
            @RequestParam(required = false) String agentId) {
        return ApiResponse.ok(agentSessionService.messages(currentUserId(session), agentId, id));
    }

    /** 重命名会话标题（侧边栏双击触发）；写独立 session_title，流式回答进行中也可安全调用 */
    @PutMapping("/sessions/{id}/title")
    public ApiResponse<Map<String, Object>> rename(@PathVariable String id,
            @Valid @RequestBody SessionTitleRequest request, HttpSession session,
            @RequestParam(required = false) String agentId) {
        return ApiResponse.ok(agentSessionService.rename(currentUserId(session), agentId, id, request.title()));
    }

    /** 删除会话（?agentId= 自建智能体会话；含 AI 记忆） */
    @DeleteMapping("/sessions/{id}")
    public ApiResponse<Map<String, Object>> delete(@PathVariable String id, HttpSession session,
            @RequestParam(required = false) String agentId) {
        return ApiResponse.ok(agentSessionService.delete(currentUserId(session), agentId, id));
    }

    /** 优雅停止某会话进行中的回答：触发框架 USER 中断（落盘用户问题 + 部分回答），而非拆断连接 */
    @PostMapping("/sessions/{id}/stop")
    public ApiResponse<Map<String, Object>> stop(@PathVariable String id, HttpSession session) {
        String userId = currentUserId(session);
        boolean stopped = agentChatService.stop(id, userId);
        if (!stopped) {
            throw new ApiException(409, "该会话当前没有进行中的回答");
        }
        return ApiResponse.ok(Map.of("stopped", true));
    }

    /** 拦截器已保证登录；此处兜底防御性校验 */
    private static String currentUserId(HttpSession session) {
        SessionUser u = SessionUser.of(session);
        if (u == null) {
            throw new ApiException(401, "未登录或会话已过期");
        }
        return u.userId();
    }

    /** 当前用户角色（清单过滤用；未登录防御性按 USER 最小权限） */
    private static String currentRole(HttpSession session) {
        SessionUser u = SessionUser.of(session);
        return u == null ? UserEntry.ROLE_USER : u.role();
    }
}
