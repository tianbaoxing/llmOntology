package com.conca.ontology.eval.dto.request;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * 自定义评测器试跑请求（单次判分即时返回，不落盘）：body 为一条临时样本，
 * 与预置 response 纯判分模式同构（无 agent 轨迹，context 变量为「无」）。
 */
@Schema(description = "自定义评测器试跑请求（临时样本单次判分）")
public record EvalCustomGraderTestRequest(
        @Schema(description = "问题", example = "定窑白釉莲纹大碗是什么朝代的？")
        String query,
        @Schema(description = "被评测回答（必填）", example = "定窑白釉莲纹大碗是北宋时期的器物。")
        @NotBlank(message = "response 不能为空") String response,
        @Schema(description = "参考答案（可选，字符串/数字/数组）", example = "北宋")
        Object referenceResponse,
        @Schema(description = "补充评分细则（可选）")
        String rubric) {
}
