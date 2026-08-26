package com.conca.ontology.controller.api;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.conca.ontology.auth.SessionUser;
import com.conca.ontology.dto.ApiResponse;
import com.conca.ontology.dto.request.EvalRunRequest;
import com.conca.ontology.eval.EvalService;
import com.conca.ontology.exception.ApiException;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;

/**
 * 评测中心入口：
 * GET  /api/eval/datasets                 → 数据集列表（data/eval/datasets/*.jsonl）
 * GET  /api/eval/datasets/{name}/file     → 数据集预览（前 20 行）
 * GET  /api/eval/graders                  → 评测器清单（注册的 Spring Bean）
 * POST /api/eval/runs                     → 创建评测任务（异步，返回 taskId 供轮询；运行中 409）
 * GET  /api/eval/runs                     → 历史任务列表（结果目录扫描）
 * GET  /api/eval/runs/{id}                → 任务进度/详情（完成后含完整 result）
 * GET  /api/eval/runs/{id}/file           → 下载完整结果 JSON
 * 评测会话按登录用户隔离（agent 记忆 userId = 发起者，sessionId = 任务 id）。
 */
@RestController
@RequestMapping("/api/eval")
@Tag(name = "eval", description = "评测中心：数据集驱动 agent 问答并多评测器判分（一期为规则评测器）")
public class EvalController {

    private final EvalService evalService;

    public EvalController(EvalService evalService) {
        this.evalService = evalService;
    }

    @GetMapping("/datasets")
    @Operation(summary = "数据集列表", description = "扫描 data/eval/datasets 目录，返回文件名与非空行数")
    public ApiResponse<Map<String, Object>> datasets() {
        return ApiResponse.ok(evalService.listDatasets());
    }

    @GetMapping("/datasets/{name}/file")
    @Operation(summary = "数据集预览", description = "返回数据集前 20 行（逐行解析，坏行标记 valid=false）")
    @Parameter(name = "name", description = "数据集文件名（如 demo.jsonl）", example = "demo.jsonl")
    public ApiResponse<Map<String, Object>> datasetPreview(@PathVariable String name) {
        return ApiResponse.ok(evalService.datasetPreview(name, 20));
    }

    @GetMapping("/graders")
    @Operation(summary = "评测器清单", description = "当前已注册的评测器（名称/描述/类型 rule|llm）")
    public ApiResponse<Map<String, Object>> graders() {
        return ApiResponse.ok(evalService.graderList());
    }

    @PostMapping("/runs")
    @Operation(summary = "创建评测任务", description = "异步执行，返回 taskId；同一时间仅允许一个任务（409）；"
            + "agent 未装配（未配置 OPENROUTER_API_KEY）时 409")
    public ApiResponse<Map<String, Object>> start(@Valid @RequestBody EvalRunRequest request,
            HttpSession session) {
        SessionUser user = SessionUser.of(session);
        if (user == null) {
            throw new ApiException(401, "未登录或会话已过期");
        }
        List<String> graders = request.graders() == null ? List.of() : request.graders();
        return ApiResponse.ok(evalService.start(request.dataset(), graders, user.userId()));
    }

    @GetMapping("/runs")
    @Operation(summary = "历史任务列表", description = "扫描结果目录（按时间倒序），含各任务汇总指标")
    public ApiResponse<Map<String, Object>> listRuns() {
        return ApiResponse.ok(evalService.listRuns());
    }

    @GetMapping("/runs/{id}")
    @Operation(summary = "任务进度/详情", description = "运行中返回实时进度；完成后（含服务重启后的历史）返回完整结果")
    @Parameter(name = "id", description = "任务 id（eval-时间-随机码）", example = "eval-20260826-153000-a1b")
    public ApiResponse<Map<String, Object>> progress(@PathVariable String id) {
        return ApiResponse.ok(evalService.progress(id));
    }

    @GetMapping("/runs/{id}/file")
    @Operation(summary = "下载评测结果", description = "下载完整结果 JSON（样本明细 + 轨迹 + 各评测器得分）")
    public ResponseEntity<Resource> download(@PathVariable String id) {
        Path path = evalService.requireResultFile(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + path.getFileName() + "\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new FileSystemResource(path));
    }
}
