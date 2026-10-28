package com.conca.ontology.agent.config;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.conca.ontology.agent.tool.ChartPluginTool;
import com.conca.ontology.agent.tool.CurrentTimeTool;
import com.conca.ontology.agent.tool.GetLocationTool;
import com.conca.ontology.agent.tool.SystemResourceTool;
import com.conca.ontology.disjoint.tool.DisjointRulesTool;
import com.conca.ontology.agent.tool.HttpRequestOntologyQueryTool;
import com.conca.ontology.disjoint.service.DisjointRuleService;
import com.conca.ontology.agent.service.GeoLocationService;
import com.conca.ontology.shared.db.AgentDistributedStores;
import com.conca.ontology.shared.db.MySqlAgentStateStores;

import io.agentscope.core.studio.StudioManager;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec;
import io.agentscope.harness.agent.workspace.LocalFsMode;
import com.conca.ontology.agent.middleware.ContentSecurityMiddleware;
import com.conca.ontology.agent.middleware.PathBoundaryMiddleware;
import com.conca.ontology.agent.middleware.RolePermissionMiddleware;
import com.conca.ontology.content.service.ContentSecurityService;
import com.conca.ontology.user.service.RolePermissionService;

/**
 * AgentScope 2.0 Harness 装配：
 * OpenAI 兼容协议模型（OpenRouter）+ HarnessAgent（ReAct 循环 + 事件流）。
 *
 * 工具层仅注册 HttpRequestOntologyQueryTool——一个无业务语义的哑 HTTP 管道（path 白名单 /api/llm/），
 * 查询协议（端点速查、只读 Cypher 红线、执行主线）由 ontology-query skill 承载，
 * 保证"查询经由 skills"；外部 Qwen CLI 调用方无此工具，走 skill 中的 execute + curl 通道。
 * harness 内置的 execute 在 Windows 下存在参数死锁（working_directory 单引号拼接），
 * skill 已约束本项目环境禁止调用 execute。
 *
 * @author tianbaoxing
 * @date 2026-08-20
 */
@Configuration
public class AgentScopeConfig {

    private static final Logger log = LoggerFactory.getLogger(AgentScopeConfig.class);

    /**
     * 平台内置 HTTP 工具（graph-query 管道等）回连本服务的 base-url。
     * 解析优先级：app.agent.ontology-base-url → 环境变量 ONTOLOGY_BASE_URL → 本机回环端口。
     * 主智能体与自建智能体注册表（AgentRegistry）共用，避免两处各算一份。
     */
    @Bean("llmBaseUrl")
    public String llmBaseUrl(
            @Value("${app.agent.ontology-base-url:${ONTOLOGY_BASE_URL:}}") String ontologyBaseUrl,
            @Value("${server.port:9876}") int serverPort) {
        return (ontologyBaseUrl == null || ontologyBaseUrl.isBlank())
                ? "http://127.0.0.1:" + serverPort
                : ontologyBaseUrl.trim().replaceAll("/+$", "");
    }

    @Bean(destroyMethod = "close")
    public HarnessAgent harnessAgent(
            @Value("${app.title:图谱}") String title,
            @Value("${app.agent.base-url}") String baseUrl,
            @Value("${app.agent.api-key:}") String apiKey,
            @Value("${app.agent.model}") String model,
            @Value("${app.agent.workspace-dir}") String workspaceDir,
            @Value("${app.agent.data-root:data}") String dataRootCfg,
            @Value("${app.agent.skills-dir:}") String skillsDir,
            @Value("${app.agent.distributed-store.enabled:false}") boolean distributedStoreEnabled,
            @Value("${app.agent.studio-url:}") String studioUrl,
            @Value("${app.mcp.weather.enabled:false}") boolean mcpWeatherEnabled,
            @Value("${app.mcp.weather.url:http://localhost:8766/mcp}") String mcpWeatherUrl,
            @Qualifier("llmBaseUrl") String llmBaseUrl,
            javax.sql.DataSource dataSource,
            DisjointRuleService disjointRuleService,
            GeoLocationService geoLocationService,
            ContentSecurityService contentSecurityService,
            RolePermissionService rolePermissionService) throws Exception {

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
        // 框架固定约定：工作区技能目录 = <workspace>/skills（SKILLS_DIR 常量，目录名不可改）；
        // 加载前有 isDirectory 检查，必须预先创建才会被识别
        Files.createDirectories(Path.of(workspaceDir, "skills"));

        // 防线二：文件边界中间件（构造时校验数据根存在、工作区在其内，fail-fast）
        PathBoundaryMiddleware boundary = new PathBoundaryMiddleware(dataRootCfg, Path.of(workspaceDir));

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
        // 哑 HTTP 管道：base-url 服务端固定（llmBaseUrl Bean 统一解析），agent 只传 path（前缀白名单 /api/llm/）
        toolkit.registerTool(new HttpRequestOntologyQueryTool(llmBaseUrl));
        // 互斥约束词典工具：agent 侧幻觉抑制。有 tool 就优先调工具；工具不可用时走
        // /api/llm/disjoint-rules HTTP 通道兜底。
        toolkit.registerTool(new DisjointRulesTool(disjointRuleService));
        toolkit.registerTool(new ChartPluginTool());
        // 当前时间工具：时效性问题先取服务端时钟，抑制凭训练记忆猜日期
        toolkit.registerTool(new CurrentTimeTool());
        // 位置工具：本地化问题先取访问者位置（客户端 IP 经 ctx 注入，GeoLite2 本地查询）
        toolkit.registerTool(new GetLocationTool(geoLocationService));
        // 系统资源工具：JVM 堆/内存池/GC/CPU/线程实时快照 + 手动触发 GC
        toolkit.registerTool(new SystemResourceTool());
        log.info("AgentScope Toolkit 已注册：{}", toolkit.getToolNames());

        // MCP 外接工具（可选）：Toolkit 原生 registerMcpClient 注册，工具 isMcp=true，
        // 追踪/Studio 自动识别 tool_source=mcp；MCP 服务未启动时降级告警，不阻断主服务
        if (mcpWeatherEnabled) {
            registerMcpWeather(toolkit, mcpWeatherUrl);
        }
        
        HarnessAgent.Builder builder = HarnessAgent.builder()
                .name("ontology-assistant")
                .description(title + "问答助手")
                .sysPrompt(sysPrompt)
                .model(chatModel)
                .workspace(Path.of(workspaceDir));
        if (distributedStoreEnabled) {
            // 工作区共享存储（四链统一架构）：运行时产物（记忆/会话/技能缓存）写入 MySQL KV 表
            // agent_kv_main，多副本共享；本地 workspace 目录降级为只读种子（两层读兜底）。
            // 会话状态收编进 distributedStore（内部仍为 agent_state_main 同款表，存量会话零迁移），
            // 不再显式 .stateStore；USER 级隔离：KV 命名空间按登录用户区分，记忆/会话天然多租户
            builder.distributedStore(AgentDistributedStores.create(dataSource,
                            MySqlAgentStateStores.MAIN, AgentDistributedStores.KV_MAIN))
                    .filesystem(new RemoteFilesystemSpec().isolationScope(IsolationScope.USER));
        } else {
            // 本机模式（现状）：防线一 harness 文件工具允许根收敛为数据根（data/），ROOTED 模式界外拒绝
            builder.filesystem(new LocalFilesystemSpec()
                            .project(boundary.dataRoot())
                            .projectWritable(true)
                            .mode(LocalFsMode.ROOTED))
                    .stateStore(MySqlAgentStateStores.create(dataSource, MySqlAgentStateStores.MAIN));
        }
        builder.maxIters(16)
                .toolkit(toolkit)
                .middleware(new ContentSecurityMiddleware(contentSecurityService))
                // 角色权限拦截（order=2，内置中间件外层）：onActing 硬拦被禁工具/
                // agent_spawn(agent_id)/load_skill_through_path(skillId)，
                // onSystemPrompt 追加权限提示；技能隐藏由 SkillFilter（ctx 注入）承担
                .middleware(new RolePermissionMiddleware(rolePermissionService))
                // 文件边界拦截（order=3，角色权限外层）：界外路径 fail-closed 拒绝
                .middleware(boundary);
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

    /**
     * 注册 weather MCP 客户端：优先 Streamable HTTP（官方主推），
     * 失败退回 SSE（weather-mcp 同端口暴露 legacy /sse 端点）。
     * 任何失败仅降级告警，不阻断主服务。
     */
    private static void registerMcpWeather(Toolkit toolkit, String url) {
        McpClientWrapper streamable = null;
        try {
            streamable = McpClientBuilder.create("weather")
                    .streamableHttpTransport(url)
                    .timeout(Duration.ofSeconds(15))
                    .buildSync();
        } catch (Exception e) {
            log.warn("MCP 客户端 [weather] Streamable 构建失败：{}", e.getMessage());
        }
        if (streamable != null && registerMcp(toolkit, streamable, url)) {
            return;
        }
        // SSE 兜底：base url = 去掉 /mcp 后缀
        String base = url.replaceAll("(?i)/mcp/?$", "");
        try {
            McpClientWrapper sse = McpClientBuilder.create("weather")
                    .sseTransport(base)
                    .timeout(Duration.ofSeconds(15))
                    .buildSync();
            registerMcp(toolkit, sse, base);
        } catch (Exception e) {
            log.warn("MCP 客户端 [weather] SSE 兜底也失败，本次运行不提供 MCP 工具：{}", e.getMessage());
        }
    }

    /** 执行 initialize 握手并注册远端工具；成功打日志并挂关闭钩子，失败关连接返回 false。 */
    private static boolean registerMcp(Toolkit toolkit, McpClientWrapper wrapper, String url) {
        try {
            toolkit.registerMcpClient(wrapper).block(Duration.ofSeconds(20));
            log.info("MCP 客户端 [weather] 已注册（url={}），工具清单：{}", url, toolkit.getToolNames());
            Runtime.getRuntime().addShutdownHook(new Thread(wrapper::close));
            return true;
        } catch (Exception e) {
            log.warn("MCP 客户端 [weather] 注册失败（url={}）：{}", url, e.getMessage());
            try {
                wrapper.close();
            } catch (Exception ignore) {
                // 忽略关闭异常
            }
            return false;
        }
    }
}
