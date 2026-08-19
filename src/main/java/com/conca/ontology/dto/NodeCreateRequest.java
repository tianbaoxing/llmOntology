package com.conca.ontology.dto;

import java.util.Map;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

/** 单节点创建（MERGE 幂等，存在则覆盖属性） */
public record NodeCreateRequest(
        @NotBlank String label,
        @NotEmpty Map<String, Object> props) {
}
