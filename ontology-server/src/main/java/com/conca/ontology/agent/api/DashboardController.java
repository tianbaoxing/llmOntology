package com.conca.ontology.agent.api;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.conca.ontology.user.web.SessionUser;
import com.conca.ontology.shared.web.ApiResponse;
import com.conca.ontology.shared.web.ApiException;
import com.conca.ontology.agent.service.DashboardService;
import com.conca.ontology.agent.service.SessionDetailService;

import jakarta.servlet.http.HttpSession;

/**
 * 驾驶舱统计入口（登录即可，统计当前用户自己的用量）：
 * GET /api/dashboard/stats → { summary, daily, sessions }
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;
    private final SessionDetailService sessionDetailService;

    public DashboardController(DashboardService dashboardService, SessionDetailService sessionDetailService) {
        this.dashboardService = dashboardService;
        this.sessionDetailService = sessionDetailService;
    }

    /** token 用量与调用量统计：summary 总量+今日 / daily 按日聚合 / sessions 会话明细 */
    @GetMapping("/stats")
    public ApiResponse<Map<String, Object>> stats(HttpSession session) {
        SessionUser u = SessionUser.of(session);
        if (u == null) {
            throw new ApiException(401, "未登录或会话已过期");
        }
        return ApiResponse.ok(dashboardService.stats(u.userId()));
    }

    /** 管理员查看指定用户的用量统计（不校验用户存在，无数据返回全零） */
    @GetMapping("/stats/{userId}")
    public ApiResponse<Map<String, Object>> statsOfUser(@PathVariable String userId, HttpSession session) {
        SessionUser u = SessionUser.of(session);
        if (u == null) {
            throw new ApiException(401, "未登录或会话已过期");
        }
        if (!u.isAdmin()) {
            throw new ApiException(403, "仅管理员可查看其他用户的驾驶舱");
        }
        return ApiResponse.ok(dashboardService.stats(userId));
    }

    /** 会话详情（summary 全会话累计 / breakdown 最后一次调用输入构成 / messages 全量明细）：不带 userId 查自己，带 userId 仅 ADMIN */
    @GetMapping("/sessions/{sessionId}/detail")
    public ApiResponse<Map<String, Object>> sessionDetail(@PathVariable String sessionId,
            @RequestParam(required = false) String userId, HttpSession session) {
        SessionUser u = SessionUser.of(session);
        if (u == null) {
            throw new ApiException(401, "未登录或会话已过期");
        }
        String target = u.userId();
        if (userId != null && !userId.isBlank()) {
            if (!u.isAdmin()) {
                throw new ApiException(403, "仅管理员可查看其他用户的会话详情");
            }
            target = userId;
        }
        return ApiResponse.ok(sessionDetailService.detail(target, sessionId));
    }
}
