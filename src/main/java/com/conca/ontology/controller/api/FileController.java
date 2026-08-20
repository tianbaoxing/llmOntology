package com.conca.ontology.controller.api;

import java.util.Map;

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

import com.conca.ontology.dto.ApiResponse;
import com.conca.ontology.dto.FileDescRequest;
import com.conca.ontology.service.FileStorageService;

/**
 * 管理端入口：上传文件管理（文件实体在 files/，元数据在 data/file-metadata.json）。
 * 上传仅放行文本类格式（pdf/txt/csv 等，白名单可配），同名覆盖；删除连带元数据记录。
 */
@RestController
@RequestMapping("/api/files")
public class FileController {

    private final FileStorageService storageService;

    public FileController(FileStorageService storageService) {
        this.storageService = storageService;
    }

    /** 上传文件（multipart：file + description），同名覆盖 */
    @PostMapping
    public ApiResponse<Map<String, Object>> upload(@RequestParam("file") MultipartFile file,
                                                   @RequestParam(value = "description", required = false) String description) {
        return ApiResponse.ok(storageService.upload(file, description));
    }

    /** 文件列表（与目录对账后返回） */
    @GetMapping
    public ApiResponse<Map<String, Object>> list() {
        return ApiResponse.ok(storageService.list());
    }

    /** 编辑文件说明（{*name} 支持子目录路径，入参带前导斜杠需截去） */
    @PutMapping("/{*name}")
    public ApiResponse<Map<String, Object>> updateDescription(@PathVariable String name,
                                                              @Valid @RequestBody FileDescRequest req) {
        return ApiResponse.ok(storageService.updateDescription(stripSlash(name), req.description()));
    }

    /** 删除文件（实体 + 元数据） */
    @DeleteMapping("/{*name}")
    public ApiResponse<Map<String, Object>> delete(@PathVariable String name) {
        return ApiResponse.ok(storageService.delete(stripSlash(name)));
    }

    private static String stripSlash(String name) {
        return name.startsWith("/") ? name.substring(1) : name;
    }
}
