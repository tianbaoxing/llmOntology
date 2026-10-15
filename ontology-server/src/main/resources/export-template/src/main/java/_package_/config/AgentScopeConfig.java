package {{PACKAGE_NAME}}.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import {{PACKAGE_NAME}}.tool.HttpJsonTool;

import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.harness.agent.HarnessAgent;

/**
 * AgentScope 2.0 Harness 装配（写法对齐 llmOntology 平台 AgentScopeConfig）：
 * OpenAI 兼容协议模型 + HarnessAgent（ReAct 循环 + 事件流），槽位全部由 application.yml（agent.*）驱动。
 *
 * - 技能：workspace/skills（整目录自平台技能库拷贝，harness 自动扫描 SKILL.md）
 * - 子智能体：workspace/subagents/*.md（声明式；已禁用运行时动态 spawn）
 * - HTTP 工具：HttpJsonTool（通用 JSON 管道，path 白名单约束，见 tool/HttpJsonTool.java）
 * - MCP：Streamable HTTP 优先注册，失败自动退回 SSE
 */

/**
 * @author tianbaoxing
 * @date 2026-09-29
 */
@Configuration
public class AgentScopeConfig {

    private static final Logger log = LoggerFactory.getLogger(AgentScopeConfig.class);
    private static final Duration MCP_CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration MCP_INIT_TIMEOUT = Duration.ofSeconds(20);

    @Bean(destroyMethod = "close")
    public HarnessAgent harnessAgent(AgentProperties props) throws IOException {
        String apiKey = props.model() == null ? "" : props.model().apiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("模型密钥未配置：请设置环境变量 AGENT_API_KEY（或 agent.model.api-key）后重启");
        }

        OpenAIChatModel chatModel = OpenAIChatModel.builder()
                .baseUrl(props.model().baseUrl())
                .apiKey(apiKey)
                .modelName(props.model().modelName())
                .stream(true)
                .build();

        // 框架固定约定：workspace/skills 与 workspace/subagents 加载前有 isDirectory 检查，须预先创建
        Path workspace = Path.of(orDefault(props.workspaceDir(), "workspace"));
        Files.createDirectories(workspace.resolve("skills"));
        Files.createDirectories(workspace.resolve("subagents"));
        Path stateDir = Path.of(orDefault(props.stateDir(), "data/state"));
        Files.createDirectories(stateDir);

        Toolkit toolkit = new Toolkit();
        for (AgentProperties.HttpTool t : orEmpty(props.httpTools())) {
            toolkit.registerAgentTool(new HttpJsonTool(
                    t.name(), t.description(), t.endpoint(), t.pathPrefix()));
        }
        for (AgentProperties.Mcp m : orEmpty(props.mcps())) {
            registerMcp(toolkit, m);
        }
        log.info("AgentScope Toolkit 已注册：{}", toolkit.getToolNames());

        HarnessAgent agent = HarnessAgent.builder()
                .name(orDefault(props.name(), "agent"))
                .description(orDefault(props.description(), props.name()))
                .sysPrompt(effectivePrompt(props))
                .model(chatModel)
                .workspace(workspace)
                .stateStore(new JsonFileAgentStateStore(stateDir))
                .maxIters(clampIters(props.maxIters()))
                .toolkit(toolkit)
                // 子智能体仅认 workspace/subagents/*.md 声明，禁止模型运行时自主 spawn
                // （与平台自建智能体装配同一开关组合，行为与平台内试运行一致）
                .disableDynamicSubagents()
                .disableDefaultWorkspaceSkills()
                .build();
        log.info("智能体「{}」已装配（workspace={}，state={}）", props.name(), workspace, stateDir);
        return agent;
    }

    // ---------------- MCP 注册（Streamable HTTP 优先，SSE 兜底） ----------------

    private static void registerMcp(Toolkit toolkit, AgentProperties.Mcp cfg) {
        String name = cfg.name().isBlank() ? "mcp-" + Integer.toHexString(cfg.url().hashCode()) : cfg.name();
        if ("sse".equalsIgnoreCase(cfg.transport())) {
            tryRegister(toolkit, name, sseBase(cfg.url()), false);
            return;
        }
        if (tryRegister(toolkit, name, cfg.url(), true)) {
            return;
        }
        tryRegister(toolkit, name, sseBase(cfg.url()), false);
    }

    /** 注册成功打日志并挂关闭钩子；失败仅降级告警，不阻断启动 */
    private static boolean tryRegister(Toolkit toolkit, String name, String url, boolean streamable) {
        McpClientBuilder builder = McpClientBuilder.create(name).timeout(MCP_CONNECT_TIMEOUT);
        if (streamable) {
            builder.streamableHttpTransport(url);
        } else {
            builder.sseTransport(url);
        }
        try {
            // wrapper 单次赋值（effectively final），供关闭钩子 lambda 捕获
            McpClientWrapper wrapper = builder.buildSync();
            try {
                toolkit.registerMcpClient(wrapper).block(MCP_INIT_TIMEOUT);
            } catch (Exception e) {
                closeQuietly(wrapper);
                throw e;
            }
            log.info("MCP 客户端 [{}] 已注册（transport={}，url={}）", name, streamable ? "streamable" : "sse", url);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> closeQuietly(wrapper)));
            return true;
        } catch (Exception e) {
            log.warn("MCP 客户端 [{}] 注册失败（transport={}，url={}）：{}",
                    name, streamable ? "streamable" : "sse", url, e.getMessage());
            return false;
        }
    }

    /** SSE 兜底：base url = 去掉 /mcp 后缀 */
    private static String sseBase(String url) {
        return url.replaceAll("(?i)/mcp/?$", "");
    }

    private static void closeQuietly(McpClientWrapper wrapper) {
        if (wrapper != null) {
            try {
                wrapper.close();
            } catch (Exception ignore) {
                // 忽略关闭异常
            }
        }
    }

    // ---------------- 槽位收口 ----------------

    /** 与平台同语义：空提示词回退最小可用中文提示词 */
    private static String effectivePrompt(AgentProperties props) {
        if (props.sysPrompt() != null && !props.sysPrompt().isBlank()) {
            return props.sysPrompt();
        }
        return "你是「" + orDefault(props.name(), "agent") + "」。请用中文回答用户问题，回答需简洁、准确、有条理。"
                + "当问题涉及事实信息而不确定时，如实说明，不要编造。";
    }

    /** 0 = 默认 6；上限 20 */
    private static int clampIters(Integer maxIters) {
        if (maxIters == null || maxIters <= 0) {
            return 6;
        }
        return Math.min(maxIters, 20);
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }
}
