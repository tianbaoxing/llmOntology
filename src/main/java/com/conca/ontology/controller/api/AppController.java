package com.conca.ontology.controller.api;

import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.conca.ontology.dto.ApiResponse;

/** 应用信息入口：管理页标题（app.title，启动期可配置）。 */
@RestController
@RequestMapping("/api/app")
public class AppController {

    private final String title;

    public AppController(@Value("${app.title:国博藏品图谱 · 管理控制台}") String title) {
        this.title = title;
    }

    @GetMapping
    public ApiResponse<Map<String, String>> info() {
        return ApiResponse.ok(Map.of("title", title));
    }
}
