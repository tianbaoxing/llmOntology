package com.conca.ontology.dto.result;

import io.swagger.v3.oas.annotations.media.Schema;

/** 创建节点结果（MERGE 幂等：已存在则覆盖属性） */
@Schema(description = "创建节点结果（MERGE 幂等：已存在则覆盖属性）")
public record NodeCreateResult(
        @Schema(description = "节点标签", example = "Era") String label,
        @Schema(description = "该类唯一键属性名（服务端按 schema 决定）", example = "name") String keyProp,
        @Schema(description = "唯一键值（写入时使用的值）") Object key,
        @Schema(description = "执行前节点是否已存在：true=本次为覆盖更新，false=本次为新建", example = "false") boolean existed) {
}
