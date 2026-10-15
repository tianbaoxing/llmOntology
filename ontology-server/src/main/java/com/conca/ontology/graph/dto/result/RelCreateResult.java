package com.conca.ontology.graph.dto.result;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 创建关系结果（MERGE 幂等：已存在则覆盖属性）
 *
 * @author tianbaoxing
 * @date 2026-08-21
 */
@Schema(description = "创建关系结果（MERGE 幂等：已存在则覆盖属性）")
public record RelCreateResult(
        @Schema(description = "关系类型", example = "SUB_ERA_OF") String type,
        @Schema(description = "起点节点唯一键值") String from,
        @Schema(description = "终点节点唯一键值") String to,
        @Schema(description = "执行前关系是否已存在：true=本次为覆盖更新，false=本次为新建", example = "false") boolean existed) {
}
