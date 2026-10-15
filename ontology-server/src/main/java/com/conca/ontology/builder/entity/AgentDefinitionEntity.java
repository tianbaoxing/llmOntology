package com.conca.ontology.builder.entity;

import java.util.List;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.conca.agentkit.AgentDefinition;

/**
 * 智能体定义行（agent_definition，P5：每智能体一行）。
 * 与框架 {@link AgentDefinition} record 经 {@link com.conca.ontology.builder.converter.AgentDefinitionConverter} 互转
 * （record 为外部框架类不可修改）。
 * 五个清单列为类型化 JSON（保序，空即 []）；created_at/updated_at 存 ISO-8601 原文保纳秒精度。
 *
 * @author tianbaoxing
 * @date 2026-10-12
 */
@TableName(value = "agent_definition", autoResultMap = true)
public class AgentDefinitionEntity {

    /** 智能体 ID（目录名） */
    @TableId(value = "agent_id", type = IdType.INPUT)
    private String agentId;

    private String name;

    /** 一句话描述 */
    private String description;

    /** 系统提示词 */
    private String sysPrompt;

    /** 开场白 */
    private String greeting;

    /** 模型地址（model.baseUrl 拆列） */
    private String modelBaseUrl;

    /** 模型名（model.modelName 拆列） */
    private String modelName;

    /** 密钥引用（model.apiKeyRef 拆列） */
    private String modelApiKeyRef;

    /** 推理轮次上限（0-20） */
    private Integer maxIters;

    /** 平台技能清单（保序） */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> skills;

    /** 内置工具白名单（保序） */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> tools;

    /** HTTP 工具定义（保序） */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<AgentDefinition.HttpToolConfig> httpTools;

    /** MCP 端点定义（保序） */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<AgentDefinition.McpConfig> mcps;

    /** 子智能体定义（保序） */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<AgentDefinition.SubagentSpec> subagents;

    /** 导出工程名 */
    private String projectName;

    /** Java 包名 */
    private String packageName;

    /** 状态：test / active */
    private String status;

    /** 所有者（创建者用户名） */
    private String owner;

    /** 定义版本（每次编辑 +1） */
    private Integer version;

    /** 最低 harness 版本 */
    private String minHarnessVersion;

    /** 创建时间 ISO-8601 原文（保纳秒） */
    private String createdAt;

    /** 更新时间 ISO-8601 原文（保纳秒，列表按此倒序） */
    private String updatedAt;

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getSysPrompt() {
        return sysPrompt;
    }

    public void setSysPrompt(String sysPrompt) {
        this.sysPrompt = sysPrompt;
    }

    public String getGreeting() {
        return greeting;
    }

    public void setGreeting(String greeting) {
        this.greeting = greeting;
    }

    public String getModelBaseUrl() {
        return modelBaseUrl;
    }

    public void setModelBaseUrl(String modelBaseUrl) {
        this.modelBaseUrl = modelBaseUrl;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public String getModelApiKeyRef() {
        return modelApiKeyRef;
    }

    public void setModelApiKeyRef(String modelApiKeyRef) {
        this.modelApiKeyRef = modelApiKeyRef;
    }

    public Integer getMaxIters() {
        return maxIters;
    }

    public void setMaxIters(Integer maxIters) {
        this.maxIters = maxIters;
    }

    public List<String> getSkills() {
        return skills;
    }

    public void setSkills(List<String> skills) {
        this.skills = skills;
    }

    public List<String> getTools() {
        return tools;
    }

    public void setTools(List<String> tools) {
        this.tools = tools;
    }

    public List<AgentDefinition.HttpToolConfig> getHttpTools() {
        return httpTools;
    }

    public void setHttpTools(List<AgentDefinition.HttpToolConfig> httpTools) {
        this.httpTools = httpTools;
    }

    public List<AgentDefinition.McpConfig> getMcps() {
        return mcps;
    }

    public void setMcps(List<AgentDefinition.McpConfig> mcps) {
        this.mcps = mcps;
    }

    public List<AgentDefinition.SubagentSpec> getSubagents() {
        return subagents;
    }

    public void setSubagents(List<AgentDefinition.SubagentSpec> subagents) {
        this.subagents = subagents;
    }

    public String getProjectName() {
        return projectName;
    }

    public void setProjectName(String projectName) {
        this.projectName = projectName;
    }

    public String getPackageName() {
        return packageName;
    }

    public void setPackageName(String packageName) {
        this.packageName = packageName;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public String getMinHarnessVersion() {
        return minHarnessVersion;
    }

    public void setMinHarnessVersion(String minHarnessVersion) {
        this.minHarnessVersion = minHarnessVersion;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = updatedAt;
    }
}
