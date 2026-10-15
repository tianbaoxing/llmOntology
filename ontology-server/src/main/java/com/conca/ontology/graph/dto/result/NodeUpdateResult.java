package com.conca.ontology.graph.dto.result;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 更新节点属性结果（keyProp 不可改，仅非键属性生效）
 *
 * @author tianbaoxing
 * @date 2026-08-21
 */
@Schema(description = "更新节点属性结果（keyProp 不可改，仅非键属性生效）")
public record NodeUpdateResult(
        @Schema(description = "节点标签", example = "Era") String label,
        @Schema(description = "节点唯一键值") String key,
        @Schema(description = "实际写入的属性个数（剔除 keyProp 后）", example = "1") int updatedProps) {
}
