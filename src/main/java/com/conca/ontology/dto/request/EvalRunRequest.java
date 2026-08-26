package com.conca.ontology.dto.request;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * 创建评测任务请求：dataset 为数据集文件名（data/eval/datasets/ 下）；
 * graders 为启用的评测器名列表（空 = 全部已注册评测器）。
 */
@Schema(description = "创建评测任务请求")
public record EvalRunRequest(
        @Schema(description = "数据集文件名（如 demo.jsonl）", example = "demo.jsonl")
        @NotBlank(message = "dataset 不能为空") String dataset,
        @Schema(description = "启用的评测器名列表（空 = 全部）", example = "[\"string_contains\",\"cypher_match\"]")
        List<String> graders) {
}
