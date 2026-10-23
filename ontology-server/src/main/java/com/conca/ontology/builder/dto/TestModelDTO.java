package com.conca.ontology.builder.dto;

/**
 * 模型连通性测试请求（向导第 2 步「测试连接」）。
 * 不含 apiKey：密钥取服务端环境变量 AGENT_API_KEY。
 *
 * @author tianbaoxing
 * @date 2026-09-24
 */
public record TestModelDTO(String baseUrl, String modelName) {
}
