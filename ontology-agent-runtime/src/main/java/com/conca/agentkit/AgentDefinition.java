/*
 * Decompiled with CFR 0.152.
 */
package com.conca.agentkit;

import java.util.List;

public record AgentDefinition(String id, String name, String description, String sysPrompt, String greeting, ModelConfig model, int maxIters, List<String> skills, List<String> tools, List<HttpToolConfig> httpTools, List<McpConfig> mcps, List<SubagentSpec> subagents, String status, String owner, int version, String minHarnessVersion, String createdAt, String updatedAt) {
    public static final String STATUS_TEST = "test";
    public static final String STATUS_ACTIVE = "active";
    public static final String EXPORT_API_KEY_REF = "${AGENT_API_KEY}";

    public AgentDefinition {
        id = AgentDefinition.blankToEmpty(id);
        name = AgentDefinition.blankToEmpty(name);
        description = AgentDefinition.blankToEmpty(description);
        sysPrompt = AgentDefinition.blankToEmpty(sysPrompt);
        greeting = AgentDefinition.blankToEmpty(greeting);
        skills = AgentDefinition.nullToEmpty(skills);
        tools = AgentDefinition.nullToEmpty(tools);
        httpTools = AgentDefinition.nullToEmpty(httpTools);
        mcps = AgentDefinition.nullToEmpty(mcps);
        subagents = AgentDefinition.nullToEmpty(subagents);
        status = AgentDefinition.blankToEmpty(status);
        owner = AgentDefinition.blankToEmpty(owner);
        minHarnessVersion = AgentDefinition.blankToEmpty(minHarnessVersion);
        createdAt = AgentDefinition.blankToEmpty(createdAt);
        updatedAt = AgentDefinition.blankToEmpty(updatedAt);
    }

    public AgentDefinition forExport() {
        ModelConfig m = this.model == null ? new ModelConfig("", "", "") : this.model;
        return new AgentDefinition(this.id, this.name, this.description, this.sysPrompt, this.greeting, new ModelConfig(m.baseUrl(), m.modelName(), EXPORT_API_KEY_REF), this.maxIters, this.skills, this.tools, this.httpTools, this.mcps, this.subagents, this.status, this.owner, this.version, this.minHarnessVersion, this.createdAt, this.updatedAt);
    }

    public AgentDefinition withVersion(int newVersion, String time) {
        return new AgentDefinition(this.id, this.name, this.description, this.sysPrompt, this.greeting, this.model, this.maxIters, this.skills, this.tools, this.httpTools, this.mcps, this.subagents, this.status, this.owner, newVersion, this.minHarnessVersion, this.createdAt, time);
    }

    private static String blankToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static <T> List<T> nullToEmpty(List<T> list) {
        return list == null ? List.of() : List.copyOf(list);
    }

    public record ModelConfig(String baseUrl, String modelName, String apiKeyRef) {
        public ModelConfig {
            baseUrl = AgentDefinition.blankToEmpty(baseUrl);
            modelName = AgentDefinition.blankToEmpty(modelName);
            apiKeyRef = AgentDefinition.blankToEmpty(apiKeyRef);
        }
    }

    public record SubagentSpec(String name, String description, String emoji, String color, String prompt) {
        public SubagentSpec {
            name = AgentDefinition.blankToEmpty(name);
            description = AgentDefinition.blankToEmpty(description);
            emoji = AgentDefinition.blankToEmpty(emoji);
            color = AgentDefinition.blankToEmpty(color);
            prompt = AgentDefinition.blankToEmpty(prompt);
        }
    }

    public record McpConfig(String name, String url, String transport) {
        public McpConfig {
            name = AgentDefinition.blankToEmpty(name);
            url = AgentDefinition.blankToEmpty(url);
            transport = AgentDefinition.blankToEmpty(transport);
        }
    }

    public record HttpToolConfig(String name, String description, String endpoint, String method, String pathPrefix) {
        public HttpToolConfig {
            name = AgentDefinition.blankToEmpty(name);
            description = AgentDefinition.blankToEmpty(description);
            endpoint = AgentDefinition.blankToEmpty(endpoint);
            method = AgentDefinition.blankToEmpty(method);
            pathPrefix = AgentDefinition.blankToEmpty(pathPrefix);
        }
    }
}

