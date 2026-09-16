package com.conca.ontology.dto.result;

import io.swagger.v3.oas.annotations.media.Schema;

/** 删除节点结果 */
@Schema(description = "删除节点结果")
public record NodeDeleteResult(
        @Schema(description = "节点标签", example = "Era") String label,
        @Schema(description = "节点唯一键值") String key,
        @Schema(description = "是否已删除（节点不存在时抛 404，不会返回 false）", example = "true") boolean deleted,
        @Schema(description = "是否连带删除了节点上的关系（detach=true 时）", example = "true") boolean detach) {
}
