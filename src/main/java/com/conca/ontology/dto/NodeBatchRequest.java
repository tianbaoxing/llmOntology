package com.conca.ontology.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.Map;

/**
 * 批量节点写入请求。
 * label 必须在白名单内；keyProp 以服务端 GraphSchema 为准（忽略客户端传入）。
 * items 中每条 map 必须包含唯一键属性，其余键值对作为节点属性写入。
 */
public record NodeBatchRequest(
        @NotBlank String label,
        String keyProp,
        @NotEmpty List<Map<String, Object>> items) {
}
