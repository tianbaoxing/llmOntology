package com.conca.ontology.eval.dto;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * 新建数据集请求：name 为文件名（仅字母数字点下划线连字符，自动补 .jsonl 后缀时不允许路径分隔符）。
 *
 * @author tianbaoxing
 * @date 2026-08-27
 */
@Schema(description = "新建数据集请求")
public record EvalDatasetDTO(
        @Schema(description = "数据集文件名（如 my-ds.jsonl）", example = "my-ds.jsonl")
        @NotBlank(message = "name 不能为空") String name) {
}
