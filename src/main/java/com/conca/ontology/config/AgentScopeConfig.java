package com.conca.ontology.config;

import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.harness.agent.HarnessAgent;

/**
 * AgentScope 2.0 Harness 装配：
 * OpenAI 兼容协议模型（OpenRouter）+ HarnessAgent（ReAct 循环 + 事件流）。
 * 最小可用版本：不注册业务工具（后续由独立的工具设计接入），
 * 同时禁用 harness 内置的 shell/文件系统/子 agent 等能力，只保留推理与记忆。
 */
@Configuration
public class AgentScopeConfig {

    static final String SYS_PROMPT = "你是「国博藏品图谱」的智能问答助手。"
            + "请用中文回答用户问题，回答需简洁、准确、有条理。"
            + "当问题涉及藏品、朝代、材质等事实信息而不确定时，如实说明，不要编造。";

    @Bean(destroyMethod = "close")
    public HarnessAgent harnessAgent(
            @Value("${app.agent.base-url}") String baseUrl,
            @Value("${app.agent.api-key:}") String apiKey,
            @Value("${app.agent.model}") String model,
            @Value("${app.agent.workspace-dir}") String workspaceDir,
            @Value("${app.agent.state-dir}") String stateDir) throws Exception {

        // key 未配置时返回 null（注册为 NullBean），问答接口返回明确错误，不影响其余功能
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }

        OpenAIChatModel chatModel = OpenAIChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(model)
                .stream(true)
                .build();

        Files.createDirectories(Path.of(workspaceDir));
        Files.createDirectories(Path.of(stateDir));

        return HarnessAgent.builder()
                .name("ontology-assistant")
                .description("国博藏品图谱问答助手")
                .sysPrompt(SYS_PROMPT)
                .model(chatModel)
                .workspace(Path.of(workspaceDir))
                .stateStore(new JsonFileAgentStateStore(Path.of(stateDir)))
                .maxIters(8)
                // 最小化开放面：禁用 harness 内置高危/无关能力
                .disableShellTool()
                .disableFilesystemTools()
                .disableSubagents()
                .disableDynamicSubagents()
                .disableDynamicSkills()
                .disableToolsConfig()
                .build();
    }
}
