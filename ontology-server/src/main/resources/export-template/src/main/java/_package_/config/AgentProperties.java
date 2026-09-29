package {{PACKAGE_NAME}}.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 智能体配置（application.yml: agent.* 节）——由 llmOntology 平台 9 槽位定义导出而来。
 * 二次开发时改 yml 即生效，无需改代码；字段含义见 README「配置说明」。
 */
@ConfigurationProperties(prefix = "agent")
public record AgentProperties(
        String name,
        String description,
        String greeting,
        String sysPrompt,
        Integer maxIters,
        String workspaceDir,
        String stateDir,
        Model model,
        List<HttpTool> httpTools,
        List<Mcp> mcps) {

    /** OpenAI 兼容协议模型接入（api-key 为 ${AGENT_API_KEY:} 占位，经环境变量注入） */
    public record Model(String baseUrl, String modelName, String apiKey) {
    }

    /** HTTP 工具声明（端点固定，模型只传 path 且受 pathPrefix 白名单约束） */
    public record HttpTool(String name, String description, String endpoint, String pathPrefix) {
    }

    /** MCP 外接端点（transport 留空 = Streamable HTTP 优先，失败退回 SSE；sse = 强制 SSE） */
    public record Mcp(String name, String url, String transport) {
    }
}
