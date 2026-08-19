package com.conca.ontology.dto;

import java.util.Map;

import jakarta.validation.constraints.NotEmpty;

/** 节点属性更新（keyProp 不可改） */
public record NodeUpdateRequest(
        @NotEmpty Map<String, Object> props) {
}
