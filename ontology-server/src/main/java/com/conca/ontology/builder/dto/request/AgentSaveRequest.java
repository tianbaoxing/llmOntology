package com.conca.ontology.builder.dto.request;

import java.util.List;

import com.conca.agentkit.AgentDefinition;

/**
 * 智能体创建/编辑请求（向导汇总表单）。
 * apiKey：创建必填；编辑留空 = 沿用已存密钥（三重防线：明文只进密钥库，不出接口）。
 */
public record AgentSaveRequest(
        String name,
        String description,
        String sysPrompt,
        String greeting,
        String baseUrl,
        String modelName,
        String apiKey,
        Integer maxIters,
        List<String> skills,
        List<String> tools,
        List<AgentDefinition.HttpToolConfig> httpTools,
        List<AgentDefinition.McpConfig> mcps,
        List<AgentDefinition.SubagentSpec> subagents,
        String status) {

    public AgentSaveRequest {
        name = nz(name);
        description = nz(description);
        sysPrompt = nz(sysPrompt);
        greeting = nz(greeting);
        baseUrl = nz(baseUrl);
        modelName = nz(modelName);
        apiKey = nz(apiKey);
        status = nz(status);
        skills = skills == null ? List.of() : skills;
        tools = tools == null ? List.of() : tools;
        httpTools = httpTools == null ? List.of() : httpTools;
        mcps = mcps == null ? List.of() : mcps;
        subagents = subagents == null ? List.of() : subagents;
    }

    private static String nz(String s) {
        return s == null ? "" : s.strip();
    }
}
