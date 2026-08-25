package com.conca.ontology.config;

import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.conca.ontology.agent.HttpProxyTool;

import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.core.studio.StudioManager;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.harness.agent.HarnessAgent;

/**
 * AgentScope 2.0 Harness 装配：
 * OpenAI 兼容协议模型（OpenRouter）+ HarnessAgent（ReAct 循环 + 事件流）。
 *
 * 工具层仅注册 HttpProxyTool——一个无业务语义的哑 HTTP 管道（path 白名单 /api/llm/），
 * 查询协议（端点速查、只读 Cypher 红线、执行主线）由 ontology-query skill 承载，
 * 保证"查询经由 skills"；外部 Qwen CLI 调用方无此工具，走 skill 中的 execute + curl 通道。
 * harness 内置的 execute 在 Windows 下存在参数死锁（working_directory 单引号拼接），
 * skill 已约束本项目环境禁止调用 execute。
 */
@Configuration
public class AgentScopeConfig {

    private static final Logger log = LoggerFactory.getLogger(AgentScopeConfig.class);

    @Bean(destroyMethod = "close")
    public HarnessAgent harnessAgent(
            @Value("${app.title:图谱}") String title,
            @Value("${app.agent.base-url}") String baseUrl,
            @Value("${app.agent.api-key:}") String apiKey,
            @Value("${app.agent.model}") String model,
            @Value("${app.agent.workspace-dir}") String workspaceDir,
            @Value("${app.agent.state-dir}") String stateDir,
            @Value("${app.agent.skills-dir:}") String skillsDir,
            @Value("${app.agent.studio-url:}") String studioUrl,
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

        Toolkit toolkit = new Toolkit();
        // 哑 HTTP 管道：base-url 服务端固定，agent 只传 path（前缀白名单 /api/llm/）。
        // 解析优先级：app.agent.ontology-base-url → 环境变量 ONTOLOGY_BASE_URL → 本机回环端口
        String llmBaseUrl = (ontologyBaseUrl == null || ontologyBaseUrl.isBlank())
                ? "http://127.0.0.1:" + serverPort
                : ontologyBaseUrl.trim().replaceAll("/+$", "");
        toolkit.registerTool(new HttpProxyTool(llmBaseUrl));

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
        HarnessAgent agent = builder.build();

        // AgentScope Studio 可视化对接（可选）：初始化成功后自动注册 Run、
        // 全局挂 StudioMessageHook 推送消息、全局注册 OTLP Tracer 上报 Trace；
        // Studio 未启动时降级告警，不阻断主服务
        if (studioUrl != null && !studioUrl.isBlank()) {
            try {
                StudioManager.init()
                        .studioUrl(studioUrl.trim().replaceAll("/+$", ""))
                        .project(title)
                        .initialize()
                        .block();
                log.info("AgentScope Studio 对接成功：{}（project={}）", studioUrl, title);
                Runtime.getRuntime().addShutdownHook(new Thread(StudioManager::shutdown));
            } catch (Exception e) {
                log.warn("AgentScope Studio 连接失败（{}），本次运行不推送可视化数据：{}", studioUrl, e.getMessage());
            }
        }
        return agent;
    }
}
