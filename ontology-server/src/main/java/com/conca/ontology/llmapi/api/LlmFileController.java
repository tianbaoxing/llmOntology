package com.conca.ontology.llmapi.api;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.conca.ontology.shared.web.ApiResponse;
import com.conca.ontology.file.dto.view.FileListView;
import com.conca.ontology.file.dto.view.LlmFileEntryView;
import com.conca.ontology.file.dto.view.LlmFileListView;
import com.conca.ontology.file.model.FileEntry;
import com.conca.ontology.file.service.FileStorageService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

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
                .filter(f -> !FileEntry.STATUS_FAILED.equals(f.convertStatus()))
                .map(this::toView)
                .toList();
        return ApiResponse.ok(new LlmFileListView(files));
    }

    private LlmFileEntryView toView(FileEntry f) {
        if (FileEntry.STATUS_SUCCESS.equals(f.convertStatus()) && f.mdName() != null) {
            return new LlmFileEntryView(f.mdName(), storageService.mdPathOf(f.mdName()), f.name(),
                    f.size(), f.ext(), f.description(), f.uploadedAt());
        }
        return new LlmFileEntryView(f.name(), f.path(), null,
                f.size(), f.ext(), f.description(), f.uploadedAt());
    }
}
