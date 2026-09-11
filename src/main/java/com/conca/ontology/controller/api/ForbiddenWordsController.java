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
import com.conca.ontology.service.ForbiddenWordService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Admin-ForbiddenWords", description = "禁用词管理（简化版）：用户输入禁用词列表，聊天消息命中则直接提示，不进入 LLM 思考")
@RestController
@RequestMapping("/api/forbidden-words")
public class ForbiddenWordsController {

    private final ForbiddenWordService service;

    public ForbiddenWordsController(ForbiddenWordService service) {
        this.service = service;
    }

    @Operation(summary = "全量禁用词（含 version）", description = "读取完整 forbidden-words.json")
    @GetMapping
    public ApiResponse<ForbiddenWordFile> getAll() {
        return ApiResponse.ok(service.loadAll());
    }

    @Operation(summary = "新增禁用词", description = "词唯一；已存在返回 409。body: { \"word\": \"禁用词\" }")
    @PostMapping
    public ApiResponse<ForbiddenWordFile> add(@RequestBody Map<String, String> body) {
        return ApiResponse.ok(service.add(body.get("word")));
    }

    @Operation(summary = "删除禁用词")
    @DeleteMapping("/{word}")
    public ApiResponse<ForbiddenWordFile> delete(@PathVariable String word) {
        return ApiResponse.ok(service.delete(word));
    }

    @Operation(summary = "测试某段文本命中的禁用词", description = "用于前端自测，返回命中的禁用词列表")
    @PostMapping("/check")
    public ApiResponse<List<String>> check(@RequestBody Map<String, String> body) {
        return ApiResponse.ok(service.check(body.get("text")));
    }
}
