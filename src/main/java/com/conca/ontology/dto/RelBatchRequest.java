package com.conca.ontology.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.Map;

/**
 * 批量关系写入请求。
 * type/fromLabel/toLabel 必须在白名单内；两端唯一键属性由服务端按 label 推导。
 */
public record RelBatchRequest(
        @NotBlank String type,
        @NotBlank String fromLabel,
        @NotBlank String toLabel,
        String fromKey,
        String toKey,
        @NotEmpty List<RelItem> items) {

    public record RelItem(String from, String to, Map<String, Object> props) {
    }
}
