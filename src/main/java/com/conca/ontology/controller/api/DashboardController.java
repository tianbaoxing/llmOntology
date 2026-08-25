package com.conca.ontology.controller.api;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.conca.ontology.auth.SessionUser;
import com.conca.ontology.dto.ApiResponse;
import com.conca.ontology.exception.ApiException;
import com.conca.ontology.service.DashboardService;

import jakarta.servlet.http.HttpSession;

/**
 * 驾驶舱统计入口（登录即可，统计当前用户自己的用量）：
 * GET /api/dashboard/stats → { summary, daily, sessions }
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
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
}
