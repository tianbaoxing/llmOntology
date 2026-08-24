package com.conca.ontology.config;

import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.harness.agent.HarnessAgent;

import com.conca.ontology.agent.OntologyHttpTools;

/**
 * AgentScope 2.0 Harness 装配：
 * OpenAI 兼容协议模型（OpenRouter）+ HarnessAgent（ReAct 循环 + 事件流）。
 * 最小可用版本：不注册业务工具（后续由独立的工具设计接入），
 * 同时禁用 harness 内置的 shell/文件系统/子 agent 等能力，只保留推理与记忆。
 */
@Configuration
public class AgentScopeConfig {

    @Bean(destroyMethod = "close")
    public HarnessAgent harnessAgent(
            @Value("${app.title:图谱}") String title,
            @Value("${app.agent.base-url}") String baseUrl,
            @Value("${app.agent.api-key:}") String apiKey,
            @Value("${app.agent.model}") String model,
            @Value("${app.agent.workspace-dir}") String workspaceDir,
            @Value("${app.agent.state-dir}") String stateDir,
            @Value("${app.agent.skills-dir:}") String skillsDir,
            @Value("${server.port:9876}") int serverPort,
            @Value("${app.agent.ontology-base-url:${ONTOLOGY_BASE_URL:}}") String ontologyBaseUrl) throws Exception {

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
        // 框架固定约定：工作区技能目录 = <workspace>/skills（SKILLS_DIR 常量，目录名不可改）；
        // 加载前有 isDirectory 检查，必须预先创建才会被识别
        Files.createDirectories(Path.of(workspaceDir, "skills"));

        // 可选：额外挂载自定义全局技能目录（projectGlobalSkillsDir，与工作区 skills 并存）
        Path globalSkillsDir = null;
        if (skillsDir != null && !skillsDir.isBlank()) {
            globalSkillsDir = Path.of(skillsDir);
            Files.createDirectories(globalSkillsDir);
        }

        // 系统提示词中的图谱名来自 app.title 配置，避免硬编码
        String sysPrompt = "你是「" + title + "」的智能问答助手。"
                + "请用中文回答用户问题，回答需简洁、准确、有条理。"
                + "当问题涉及事实信息而不确定时，如实说明，不要编造。";

        // 原生 HTTP 工具：模型直接以结构化调用访问 /api/llm 只读平面，
        // 绕开 execute（shell 包装层）在 Windows 下的缺陷；
        // build() 会在该 toolkit 的拷贝上继续合并注册 harness 内置工具（文件/技能等）
        String llmBaseUrl = (ontologyBaseUrl == null || ontologyBaseUrl.isBlank())
                ? "http://127.0.0.1:" + serverPort
                : ontologyBaseUrl.trim().replaceAll("/+$", "");
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new OntologyHttpTools(llmBaseUrl));

        HarnessAgent.Builder builder = HarnessAgent.builder()
                .name("ontology-assistant")
                .description(title + "问答助手")
                .sysPrompt(sysPrompt)
                .model(chatModel)
                .workspace(Path.of(workspaceDir))
                .stateStore(new JsonFileAgentStateStore(Path.of(stateDir)))
                .maxIters(8)
                .toolkit(toolkit);
                // 最小化开放面：禁用 harness 内置高危/无关能力
                // .disableShellTool()
                // .disableFilesystemTools()
                // .disableSubagents()
                // .disableDynamicSubagents()
                // .disableToolsConfig();
        // 注意：不能调用 disableDynamicSkills()——它会跳过 HarnessSkillMiddleware 装配，
        // 导致技能既不注册 skill 工具也不注入技能清单 prompt，模型完全无法感知技能
        if (globalSkillsDir != null) {
            builder.projectGlobalSkillsDir(globalSkillsDir);
        }
        return builder.build();
    }
}
