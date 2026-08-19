package com.history.ontology2.dto;

import java.util.Map;

import jakarta.validation.constraints.NotBlank;

/** 关系操作（创建/更新/删除）：type + 两端 label/键 定位 */
public record RelRequest(
        @NotBlank String type,
        @NotBlank String fromLabel,
        @NotBlank String fromKey,
        @NotBlank String toLabel,
        @NotBlank String toKey,
        Map<String, Object> props) {
}
