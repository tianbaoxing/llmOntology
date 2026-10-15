package com.conca.ontology.file.api;
import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.conca.ontology.shared.web.ApiResponse;
import com.conca.ontology.file.dto.result.FileDeleteResult;
import com.conca.ontology.file.dto.request.FileDescRequest;
import com.conca.ontology.file.dto.view.FileListView;
import com.conca.ontology.file.dto.result.FileUpdateResult;
import com.conca.ontology.file.dto.result.FileUploadResult;
import com.conca.ontology.file.service.FileStorageService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 管理端入口：上传文件管理（文件实体在 files/，元数据在 data/file-metadata.json）。
 * 上传仅放行文本类格式（pdf/txt/csv 等，白名单可配），同名覆盖；删除连带元数据记录。
 */
@Tag(name = "Admin-File", description = "知识库文件管理（files.html 数据源）：上传（白名单校验、同名覆盖）、"
        + "列表、编辑说明、删除（实体 + 元数据一并移除）。")

/**
 * @author tianbaoxing
 * @date 2026-08-20
 */
@RestController
@RequestMapping("/api/files")
public class FileController {

    private final FileStorageService storageService;

    public FileController(FileStorageService storageService) {
        this.storageService = storageService;
    }

    @Operation(summary = "上传文件", description = "multipart：file + description（可选）。扩展名白名单校验，"
            + "同名覆盖，原子落盘（tmp + rename）。")
    @PostMapping
    public ApiResponse<FileUploadResult> upload(
            @Parameter(description = "文件本体（multipart）") @RequestParam("file") MultipartFile file,
            @Parameter(description = "文件说明（可选）") @RequestParam(value = "description", required = false) String description) {
        return ApiResponse.ok(storageService.upload(file, description));
    }

    @Operation(summary = "文件列表", description = "与磁盘对账后返回（新文件补录、消失文件剔除）。")
    @GetMapping
    public ApiResponse<FileListView> list() {
        return ApiResponse.ok(storageService.list());
    }

    @Operation(summary = "编辑文件说明", description = "{*name} 支持子目录路径，入参带前导斜杠需截去。")
    @PutMapping("/{*name}")
    public ApiResponse<FileUpdateResult> updateDescription(
            @Parameter(description = "文件名（可含子目录路径）", example = "artifact.csv") @PathVariable String name,
            @Valid @RequestBody FileDescRequest req) {
        return ApiResponse.ok(storageService.updateDescription(stripSlash(name), req.description()));
    }

    @Operation(summary = "删除文件", description = "文件实体 + 对应 MD + 元数据记录一并移除。")
    @DeleteMapping("/{*name}")
    public ApiResponse<FileDeleteResult> delete(
            @Parameter(description = "文件名（可含子目录路径）", example = "artifact.csv") @PathVariable String name) {
        return ApiResponse.ok(storageService.delete(stripSlash(name)));
    }

    @Operation(summary = "重试 MD 转换", description = "对 pdf/word/excel 强制重转（先删旧 MD）；"
            + "转换失败（如扫描件）修复后手动触发。name 为查询参数（可含子目录路径）。")
    @PostMapping("/reconvert")
    public ApiResponse<FileUpdateResult> reconvert(
            @Parameter(description = "文件名（可含子目录路径）", example = "报告.docx") @RequestParam("name") String name) {
        return ApiResponse.ok(storageService.reconvert(name));
    }

    private static String stripSlash(String name) {
        return name.startsWith("/") ? name.substring(1) : name;
    }
}
