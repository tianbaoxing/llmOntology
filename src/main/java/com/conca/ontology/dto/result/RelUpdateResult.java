package com.conca.ontology.dto.result;

import io.swagger.v3.oas.annotations.media.Schema;

/** 更新关系属性结果（按 type + 两端键定位） */
@Schema(description = "更新关系属性结果（按 type + 两端键定位）")
public record RelUpdateResult(
        @Schema(description = "关系类型", example = "SUB_ERA_OF") String type,
        @Schema(description = "起点节点唯一键值") String from,
        @Schema(description = "终点节点唯一键值") String to,
        @Schema(description = "实际写入的关系属性个数", example = "1") int updatedProps) {
}
