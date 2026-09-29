package com.conca.ontology.builder.dto.request;

/**
 * 模型连通性测试请求（向导第 2 步「测试连接」）。
 * 不含 apiKey：密钥取服务端环境变量 AGENT_API_KEY。
 */
public record TestModelRequest(String baseUrl, String modelName) {
}
