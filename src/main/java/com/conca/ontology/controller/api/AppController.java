package com.conca.ontology.controller.api;

import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.conca.ontology.dto.ApiResponse;

/** 应用信息入口：管理页标题（app.title）与问答模型信息（app.agent.*，启动期可配置）。 */
@RestController
@RequestMapping("/api/app")
public class AppController {

    private final String title;
    private final String baseUrl;
    private final String model;

    public AppController(@Value("${app.title}") String title,
                         @Value("${app.agent.base-url:}") String baseUrl,
                         @Value("${app.agent.model:}") String model) {
        this.title = title;
        this.baseUrl = baseUrl;
        this.model = model;
    }

    @GetMapping
    public ApiResponse<Map<String, String>> info() {
        return ApiResponse.ok(Map.of("title", title, "baseUrl", baseUrl, "model", model));
    }
}
