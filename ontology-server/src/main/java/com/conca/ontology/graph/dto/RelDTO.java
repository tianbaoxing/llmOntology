package com.conca.ontology.graph.dto;
import java.util.Map;

import jakarta.validation.constraints.NotBlank;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 关系操作（创建/更新/删除）：type + 两端 label/键 定位
 *
 * @author tianbaoxing
 * @date 2026-08-19
 */
@Schema(description = "关系操作请求（创建/更新/删除通用）：type + 两端 label/唯一键 定位")
public record RelDTO(
        @Schema(description = "关系类型（须在 schema 白名单内且签名匹配）", example = "SUB_ERA_OF")
        @NotBlank String type,
        @Schema(description = "起点节点标签", example = "Era") @NotBlank String fromLabel,
        @Schema(description = "起点节点唯一键值", example = "北宋") @NotBlank String fromKey,
        @Schema(description = "终点节点标签", example = "Era") @NotBlank String toLabel,
        @Schema(description = "终点节点唯一键值", example = "宋") @NotBlank String toKey,
        @Schema(description = "关系属性键值对（创建/更新时生效，删除时忽略；可为空）", example = "{}")
        Map<String, Object> props) {
}
