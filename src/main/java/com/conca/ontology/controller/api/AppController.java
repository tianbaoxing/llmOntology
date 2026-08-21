package com.conca.ontology.controller.api;

import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.conca.ontology.dto.ApiResponse;
import com.conca.ontology.service.UserService;

/** 应用信息入口（公开）：标题（app.title）、问答模型信息（app.agent.*）与初始化状态（登录页据此切换模式）。 */
@RestController
@RequestMapping("/api/app")
public class AppController {

    private final String title;
    private final String baseUrl;
    private final String model;
    private final UserService users;

    public AppController(@Value("${app.title}") String title,
                         @Value("${app.agent.base-url:}") String baseUrl,
                         @Value("${app.agent.model:}") String model,
                         UserService users) {
        this.title = title;
        this.baseUrl = baseUrl;
        this.model = model;
        this.users = users;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> info() {
        return ApiResponse.ok(Map.of(
                "title", title,
                "baseUrl", baseUrl,
                "model", model,
                "initialized", users.initialized()));
    }
}
