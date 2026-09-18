package com.conca.ontology.graph.dto.request;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 批量关系写入请求。
 * type/fromLabel/toLabel 必须在白名单内；两端唯一键属性由服务端按 label 推导。
 */
@Schema(description = "批量关系写入请求（MATCH 两端 + MERGE 幂等；一端不存在的条目静默跳过）")
public record RelBatchRequest(
        @Schema(description = "关系类型（签名须匹配 schema 声明）", example = "OF_ERA") @NotBlank String type,
        @Schema(description = "起点节点标签", example = "CulturalRelic") @NotBlank String fromLabel,
        @Schema(description = "终点节点标签", example = "Era") @NotBlank String toLabel,
        @Schema(description = "已废弃：两端唯一键属性名由服务端推导，此字段被忽略") String fromKey,
        @Schema(description = "已废弃：同上") String toKey,
        @Schema(description = "关系条目数组：from/to 为两端节点唯一键值") @NotEmpty List<RelItem> items) {

    @Schema(description = "单条关系：两端唯一键 + 关系属性")
    public record RelItem(
            @Schema(description = "起点节点唯一键值", example = "定窑白釉莲纹大碗") String from,
            @Schema(description = "终点节点唯一键值", example = "北宋") String to,
            @Schema(description = "关系属性键值对（可为空）", example = "{}") Map<String, Object> props) {
    }
}
