package com.conca.ontology.controller.api;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.conca.ontology.dto.ApiResponse;
import com.conca.ontology.service.DashboardService;

/**
 * 驾驶舱统计入口（仅 ADMIN，AuthInterceptor 校验）：
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
    public ApiResponse<Map<String, Object>> stats() {
        return ApiResponse.ok(dashboardService.stats());
    }
}
