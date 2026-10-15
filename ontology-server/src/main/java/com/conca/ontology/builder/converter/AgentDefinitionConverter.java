package com.conca.ontology.builder.converter;

import java.util.List;

import com.conca.agentkit.AgentDefinition;
import com.conca.ontology.builder.entity.AgentDefinitionEntity;

/**
 * 框架 {@link AgentDefinition} record ↔ {@link AgentDefinitionEntity} 转换器（P5）。
 * AgentDefinitionStore 持久化用。
 * toEntity 维持记录不变式：五个 NOT NULL 清单列 null 归一化为空数组（record 构造器同语义）；
 * model 嵌套对象拆三列，model 为 null 时三列存空串（toModel 还原空 ModelConfig——
 * 对齐框架 forExport 的 null-model 处理，避免下游解引用 NPE）。
 */
public final class AgentDefinitionConverter {

    private AgentDefinitionConverter() {
    }

    public static AgentDefinition toModel(AgentDefinitionEntity e) {
        return new AgentDefinition(
                e.getAgentId(),
                e.getName(),
                e.getDescription(),
                e.getSysPrompt(),
                e.getGreeting(),
                new AgentDefinition.ModelConfig(e.getModelBaseUrl(), e.getModelName(), e.getModelApiKeyRef()),
                e.getMaxIters() == null ? 0 : e.getMaxIters(),
                e.getSkills(),
                e.getTools(),
                e.getHttpTools(),
                e.getMcps(),
                e.getSubagents(),
                e.getProjectName(),
                e.getPackageName(),
                e.getStatus(),
                e.getOwner(),
                e.getVersion() == null ? 0 : e.getVersion(),
                e.getMinHarnessVersion(),
                e.getCreatedAt(),
                e.getUpdatedAt());
    }

    public static AgentDefinitionEntity toEntity(AgentDefinition def) {
        AgentDefinitionEntity e = new AgentDefinitionEntity();
        e.setAgentId(def.id());
        e.setName(def.name());
        e.setDescription(def.description());
        e.setSysPrompt(def.sysPrompt());
        e.setGreeting(def.greeting());
        AgentDefinition.ModelConfig model = def.model();
        e.setModelBaseUrl(model == null ? "" : model.baseUrl());
        e.setModelName(model == null ? "" : model.modelName());
        e.setModelApiKeyRef(model == null ? "" : model.apiKeyRef());
        e.setMaxIters(def.maxIters());
        e.setSkills(nz(def.skills()));
        e.setTools(nz(def.tools()));
        e.setHttpTools(nz(def.httpTools()));
        e.setMcps(nz(def.mcps()));
        e.setSubagents(nz(def.subagents()));
        e.setProjectName(def.projectName());
        e.setPackageName(def.packageName());
        e.setStatus(def.status());
        e.setOwner(def.owner());
        e.setVersion(def.version());
        e.setMinHarnessVersion(def.minHarnessVersion());
        e.setCreatedAt(def.createdAt());
        e.setUpdatedAt(def.updatedAt());
        return e;
    }

    private static <T> List<T> nz(List<T> l) {
        return l == null ? List.of() : l;
    }
}
