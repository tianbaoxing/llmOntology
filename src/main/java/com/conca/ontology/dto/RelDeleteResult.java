package com.conca.ontology.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 删除关系结果（按 type + 两端键定位） */
@Schema(description = "删除关系结果（按 type + 两端键定位）")
public record RelDeleteResult(
        @Schema(description = "关系类型", example = "SUB_ERA_OF") String type,
        @Schema(description = "起点节点唯一键值") String from,
        @Schema(description = "终点节点唯一键值") String to,
        @Schema(description = "是否已删除（关系不存在时抛 404，不会返回 false）", example = "true") boolean deleted) {
}
