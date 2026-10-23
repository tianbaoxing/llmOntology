package com.conca.ontology.eval.dto;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * 人工评分请求：sampleId 为结果 JSON 中 samples[].id；
 * rawScore 为 1-5 星（skip=true 时可空）；comment 为可选评语；
 * skip=true 表示跳过该样本（不计入均值）。可重复提交覆盖修改（留 scoredAt 留痕）。
 *
 * @author tianbaoxing
 * @date 2026-09-03
 */
@Schema(description = "人工评分请求")
public record EvalHumanScoreDTO(
        @Schema(description = "样本 id（结果 JSON 中 samples[].id）", example = "q-20260901-100000-abc")
        @NotBlank(message = "sampleId 不能为空") String sampleId,
        @Schema(description = "评分 1-5 星（skip=true 时可为空）", example = "4")
        Integer rawScore,
        @Schema(description = "可选评语", example = "回答准确，但缺少出处")
        String comment,
        @Schema(description = "true 表示跳过该样本（不计入均值）", example = "false")
        Boolean skip) {
}
