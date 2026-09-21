package com.conca.ontology.quality.dto.view;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "未使用的 Schema 类定义：声明了但零实例")
public record UnusedClassView(
        @Schema(description = "类名（英文标识）", example = "BronzeMirror") String className,
        @Schema(description = "中文名", example = "青铜镜") String cnName,
        @Schema(description = "层归属：concept / instance", example = "instance") String layer,
        @Schema(description = "主键属性名", example = "name") String keyProp,
        @Schema(description = "语义说明") String description) {
}
