package com.conca.ontology.model.dto;

/**
 * 模型切换选项行（chat 页底部模型名弹框精简视图）：model_info 精简行
 * + 展示名推导的 OpenRouter slug（routerSlug）。
 * 大数列（parameters/trainingComputeFlop）后端已转字符串，规避 JS 精度丢失；全字段可空。
 *
 * @author tianbaoxing
 * @date 2026-10-30
 */
public record ModelOptionDTO(
        String model,
        String organization,
        String routerSlug,
        String publicationDate,
        String domain,
        String task,
        String confidence,
        String parameters,
        String trainingComputeFlop,
        Boolean foundationModel,
        Boolean frontierModel,
        Boolean openModelWeights,
        String baseModel) {
}
