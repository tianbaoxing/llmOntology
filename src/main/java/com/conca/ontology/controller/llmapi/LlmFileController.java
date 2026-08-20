package com.conca.ontology.controller.llmapi;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.conca.ontology.dto.ApiResponse;
import com.conca.ontology.service.FileStorageService;

/**
 * LLM 专属入口：只读文件列表。
 * 返回 name/path(绝对路径)/size/ext/description/uploadedAt，LLM 按绝对路径用自身工具读取文件内容。
 */
@RestController
@RequestMapping("/api/llm")
public class LlmFileController {

    private final FileStorageService storageService;

    public LlmFileController(FileStorageService storageService) {
        this.storageService = storageService;
    }

    /** 上传文件全量列表（与磁盘对账后返回） */
    @GetMapping("/files")
    public ApiResponse<Map<String, Object>> files() {
        return ApiResponse.ok(storageService.list());
    }
}
