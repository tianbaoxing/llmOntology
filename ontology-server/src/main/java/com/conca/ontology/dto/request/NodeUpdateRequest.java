package com.conca.ontology.dto.request;

import java.util.Map;

import jakarta.validation.constraints.NotEmpty;

import io.swagger.v3.oas.annotations.media.Schema;

/** 节点属性更新（keyProp 不可改） */
@Schema(description = "节点属性更新请求（SET += 增量合并；keyProp 不可改，带了也会被剔除）")
public record NodeUpdateRequest(
        @Schema(description = "待更新属性键值对（剔除 keyProp 后不能为空）", example = "{\"note\": \"x\"}")
        @NotEmpty Map<String, Object> props) {
}
