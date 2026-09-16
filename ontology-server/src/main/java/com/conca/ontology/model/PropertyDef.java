package com.conca.ontology.model;

import io.swagger.v3.oas.annotations.media.Schema;

/** 属性定义（节点或关系上） */
@Schema(description = "属性定义（节点或关系上）")
public record PropertyDef(
        @Schema(description = "属性名（[A-Za-z_][A-Za-z0-9_]*）", example = "name") String name,
        @Schema(description = "中文名", example = "年代名") String cnName,
        @Schema(description = "数据类型", example = "string",
                allowableValues = {"string", "number"}) String dataType,
        @Schema(description = "是否必填（仅文档约束，入库不强制）", example = "true") boolean required,
        @Schema(description = "语义说明") String description) {
}
