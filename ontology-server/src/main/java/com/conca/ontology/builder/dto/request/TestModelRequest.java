package com.conca.ontology.builder.dto.request;

/**
 * 模型连通性测试请求（向导第 2 步「测试连接」）。
 * key 仅用于本次测试请求，不落盘。
 */
public record TestModelRequest(String baseUrl, String modelName, String apiKey) {
}
