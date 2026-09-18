package com.conca.ontology.eval.dto.request;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/**
 * 数据集样本新增/编辑请求（编辑按非空行序号 seq 定位，不依赖 id）。
 * referenceType 决定 referenceText 的解析方式：string | number | array（空 = 不写 reference_response）。
 */
@Schema(description = "数据集样本新增/编辑请求")
public record EvalSampleRequest(
        @Schema(description = "样本 id（空 = 自动生成 q-时间戳）", example = "q001")
        String id,
        @Schema(description = "单轮问题（sampleType=single 时必填）", example = "知识图谱中一共收录了多少件文物？")
        String query,
        @Schema(description = "样本类型：single|multi（空 = 按 turns 是否为空自动判断）", example = "single")
        String sampleType,
        @Schema(description = "期望回答类型：string|number|array（空 = 不写）", example = "number")
        String referenceType,
        @Schema(description = "期望回答文本（按 referenceType 解析）", example = "5524")
        String referenceText,
        @Schema(description = "数值容差（number_accuracy 用）", example = "0.0")
        Double tolerance,
        @Schema(description = "预置回答（非空则评测时跳过 agent 直接判分）")
        String response,
        @Schema(description = "多轮脚本（sampleType=multi 时使用，至少 2 轮）")
        List<@Valid TurnRequest> turns) {

    @Schema(description = "多轮脚本中的一轮")
    public record TurnRequest(
            @Schema(description = "该轮问题（必填）", example = "它是什么朝代的？")
            @NotBlank(message = "turn query 不能为空") String query,
            @Schema(description = "该轮预置回答（可选，全部轮次非空则评测时跳过 agent 直接判分）")
            String response) {
    }
}
