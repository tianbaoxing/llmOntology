package com.conca.ontology.builder.dto.request;

/** MCP 端点握手探测请求（向导第 4 步「测试连接」，返回远端工具清单） */
public record TestMcpRequest(String url) {
}
