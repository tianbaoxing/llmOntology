package com.conca.ontology.controller.llmapi;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.conca.ontology.dto.ApiResponse;
import com.conca.ontology.dto.view.FileListView;
import com.conca.ontology.service.FileStorageService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * LLM 专属入口：只读文件列表。
 */
@Tag(name = "LLM-File", description = "知识库文件只读列表：返回 name/path(绝对路径)/size/ext/description/uploadedAt，"
        + "LLM 按绝对路径用自身工具读取文件内容。")
@RestController
@RequestMapping("/api/llm")
public class LlmFileController {

    private final FileStorageService storageService;

    public LlmFileController(FileStorageService storageService) {
        this.storageService = storageService;
    }

    @Operation(summary = "上传文件全量列表",
            description = "与磁盘对账后返回（目录新文件自动补录、已消失文件剔除）。"
                    + "path 为绝对路径，LLM 直接用文件读取工具打开。")
    @GetMapping("/files")
    public ApiResponse<FileListView> files() {
        return ApiResponse.ok(storageService.list());
    }
}
