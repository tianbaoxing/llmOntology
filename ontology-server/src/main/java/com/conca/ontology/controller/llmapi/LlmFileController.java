package com.conca.ontology.controller.llmapi;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.conca.ontology.dto.ApiResponse;
import com.conca.ontology.dto.view.FileListView;
import com.conca.ontology.dto.view.LlmFileEntryView;
import com.conca.ontology.dto.view.LlmFileListView;
import com.conca.ontology.model.FileEntry;
import com.conca.ontology.service.FileStorageService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * LLM 专属入口：只读文件列表（path 恒为"应读路径"）。
 * 二进制原件（pdf/word/excel）已自动转为 MD，path 指向 files-md/ 下的 Markdown；
 * 文本类原样返回原路径；转换 FAILED 的文件不返回（admin 页可见原因并支持重试）。
 */
@Tag(name = "LLM-File", description = "知识库文件只读列表：返回 name/path(应读绝对路径)/originName/size/ext/description/uploadedAt，"
        + "path 恒为 Markdown/文本文件，LLM 按路径用自身工具直接读取。")
@RestController
@RequestMapping("/api/llm")
public class LlmFileController {

    private final FileStorageService storageService;

    public LlmFileController(FileStorageService storageService) {
        this.storageService = storageService;
    }

    @Operation(summary = "上传文件全量列表",
            description = "与磁盘对账后返回（目录新文件自动补录、已消失文件剔除）。"
                    + "path 为可直接读取的 Markdown/文本文件绝对路径（二进制原件已自动转换）；"
                    + "转换失败的文件不返回。")
    @GetMapping("/files")
    public ApiResponse<LlmFileListView> files() {
        FileListView list = storageService.list();
        List<LlmFileEntryView> files = list.files().stream()
                // FAILED 不给 LLM（admin 页可见原因，reconvert 修复后自动出现）
                .filter(f -> !FileEntry.STATUS_FAILED.equals(f.convertStatus()))
                .map(this::toView)
                .toList();
        return ApiResponse.ok(new LlmFileListView(list.version(), files));
    }

    /** 二进制 → MD 路径（originName 溯源）；文本 → 原路径 */
    private LlmFileEntryView toView(FileEntry f) {
        if (FileEntry.STATUS_SUCCESS.equals(f.convertStatus()) && f.mdName() != null) {
            return new LlmFileEntryView(f.mdName(), storageService.mdPathOf(f.mdName()), f.name(),
                    f.size(), f.ext(), f.description(), f.uploadedAt());
        }
        return new LlmFileEntryView(f.name(), f.path(), null,
                f.size(), f.ext(), f.description(), f.uploadedAt());
    }
}
