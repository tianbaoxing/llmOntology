package com.conca.ontology.builder.dto;

import java.util.List;

import com.conca.agentkit.AgentDefinition;

/**
 * 智能体创建/编辑请求（向导汇总表单）。
 * 不含 apiKey：模型密钥统一经环境变量 AGENT_API_KEY 注入，不进表单、不落盘。
 *
 * @author tianbaoxing
 * @date 2026-09-24
 */
public record AgentSaveDTO(
        String name,
        String description,
        String sysPrompt,
        String greeting,
        String baseUrl,
        String modelName,
        Integer maxIters,
        List<String> skills,
        List<String> tools,
        List<AgentDefinition.HttpToolConfig> httpTools,
        List<AgentDefinition.McpConfig> mcps,
        List<AgentDefinition.SubagentSpec> subagents,
        String projectName,
        String packageName,
        String status) {

    public AgentSaveDTO {
        name = nz(name);
        description = nz(description);
        sysPrompt = nz(sysPrompt);
        greeting = nz(greeting);
        baseUrl = nz(baseUrl);
        modelName = nz(modelName);
        projectName = nz(projectName);
        packageName = nz(packageName);
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
