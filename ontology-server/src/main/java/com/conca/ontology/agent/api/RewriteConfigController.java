package com.conca.ontology.agent.api;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.conca.ontology.shared.web.ApiResponse;
import com.conca.ontology.agent.dto.RewriteConfig;
import com.conca.ontology.agent.service.RewriteConfigService;
import com.conca.ontology.agent.service.RewriteConfigService.ConfigView;
import com.conca.ontology.agent.service.RewriteConfigService.RewriteResult;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Admin-RewriteConfig", description = "语义改写配置：4 项功能（语义/错别字/术语/上下文）各含启用开关与"
        + "可修改提示词；输出格式提示词为只读常量。启用后聊天消息先经独立 rewrite agent 管道改写"
        + "（过程在聊天页实时展示），改写后文本再送入主 Agent")

/**
 * @author tianbaoxing
 * @date 2026-09-11
 */
@RestController
@RequestMapping("/api/rewrite-config")
public class RewriteConfigController {

    private final RewriteConfigService service;

    public RewriteConfigController(RewriteConfigService service) {
        this.service = service;
    }

    @Operation(summary = "查询改写配置", description = "4 项功能配置 + 只读输出格式提示词 + 管道顺序；"
            + "配置行缺失时返回内置默认（全部未启用）")
    @GetMapping
    public ApiResponse<ConfigView> get() {
        return ApiResponse.ok(service.view());
    }

    @Operation(summary = "保存改写配置（部分更新）", description = "body: {\"features\":{\"semantic\":{\"enabled\":true,"
            + "\"prompt\":\"...\"}}}；缺省功能沿用现值；行级保存，聊天管道热生效")
    @PutMapping
    public ApiResponse<ConfigView> save(@RequestBody SaveBody body) {
        return ApiResponse.ok(service.save(body.features()));
    }

    @Operation(summary = "测试单项功能改写", description = "无论是否启用均可测；body: {\"type\":\"semantic\","
            + "\"text\":\"...\"}；经独立 rewrite agent 执行，返回 rewritten_text 与 explanation")
    @PostMapping("/test")
    public ApiResponse<RewriteResult> test(@RequestBody TestBody body) {
        return ApiResponse.ok(service.execute(body.type(), body.text(), List.of()));
    }

    /** 保存请求体 */
    public record SaveBody(Map<String, RewriteConfig.Feature> features) {
    }

    /** 测试请求体 */
    public record TestBody(String type, String text) {
    }
}
