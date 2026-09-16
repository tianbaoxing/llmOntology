package com.conca.ontology.controller.api;

import java.nio.file.Path;
import java.util.Map;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.conca.ontology.dto.ApiResponse;
import com.conca.ontology.service.BackupService;

/**
 * 管理端入口：备份资源。
 * 备份异步执行（POST 返回 taskId，前端轮询 tasks/{taskId} 展示进度条）；
 * 列表 = 扫描备份目录文件名解析；file/删除按文件名（服务端正则校验）。
 */
@RestController
@RequestMapping("/api/backups")
public class BackupController {

    private final BackupService backupService;

    public BackupController(BackupService backupService) {
        this.backupService = backupService;
    }

    /** 启动备份（异步），返回 taskId 供轮询进度 */
    @PostMapping
    public ApiResponse<Map<String, Object>> start() {
        return ApiResponse.ok(backupService.startBackup());
    }

    /** 轮询备份任务进度 */
    @GetMapping("/tasks/{taskId}")
    public ApiResponse<Map<String, Object>> progress(@PathVariable String taskId) {
        return ApiResponse.ok(backupService.progress(taskId));
    }

    /** 备份列表（文件名解析：时间/节点数/关系数/文件数/schema版本） */
    @GetMapping
    public ApiResponse<Map<String, Object>> list() {
        return ApiResponse.ok(backupService.list());
    }

    /** 下载备份 zip */
    @GetMapping("/{name}/file")
    public ResponseEntity<Resource> download(@PathVariable String name) {
        Path path = backupService.requireBackup(name);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(new FileSystemResource(path));
    }

    /** 删除备份 */
    @DeleteMapping("/{name}")
    public ApiResponse<Map<String, Object>> delete(@PathVariable String name) {
        return ApiResponse.ok(backupService.delete(name));
    }
}
