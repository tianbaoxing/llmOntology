package com.conca.ontology.controller.api;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.conca.ontology.dto.ApiResponse;
import com.conca.ontology.model.ForbiddenWordFile;
import com.conca.ontology.service.ContentSecurityService;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@Deprecated(since = "内容安全一体化 v1")
@Tag(name = "Admin-ForbiddenWords", description = "【已迁移】请使用 /api/content-security/* 新端点，本 Controller 仅保留兼容")
@RestController
@RequestMapping("/api/forbidden-words")
public class ForbiddenWordsController {

    private final ContentSecurityService securityService;

    public ForbiddenWordsController(ContentSecurityService securityService) {
        this.securityService = securityService;
    }

    @Operation(summary = "全量禁用词（含 version）", description = "【兼容代理】转发到 /api/content-security/forbidden-words")
    @GetMapping
    public ApiResponse<ForbiddenWordFile> getAll() {
        return ApiResponse.ok(securityService.loadAllForbiddenWords());
    }

    @Operation(summary = "新增禁用词", description = "【兼容代理】转发到 /api/content-security/forbidden-words POST")
    @PostMapping
    public ApiResponse<ForbiddenWordFile> add(@RequestBody Map<String, String> body) {
        return ApiResponse.ok(securityService.addForbiddenWord(body.get("word")));
    }

    @Operation(summary = "删除禁用词", description = "【兼容代理】转发到 /api/content-security/forbidden-words DELETE")
    @DeleteMapping("/{word}")
    public ApiResponse<ForbiddenWordFile> delete(@PathVariable String word) {
        return ApiResponse.ok(securityService.deleteForbiddenWord(word));
    }

    @Operation(summary = "测试某段文本命中的禁用词", description = "【兼容代理】走新 Service 一体化检测后提取禁用词命中")
    @PostMapping("/check")
    public ApiResponse<List<String>> check(@RequestBody Map<String, String> body) {
        return ApiResponse.ok(securityService.checkUserMessages(
                List.of(Msg.builder().role(MsgRole.USER).textContent(body.get("text")).build())
        ).forbiddenWordHits());
    }
}
