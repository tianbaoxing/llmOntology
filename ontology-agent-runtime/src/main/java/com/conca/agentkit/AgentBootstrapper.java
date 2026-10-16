/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  io.agentscope.core.middleware.MiddlewareBase
 *  io.agentscope.core.model.Model
 *  io.agentscope.core.state.AgentStateStore
 *  io.agentscope.core.state.JsonFileAgentStateStore
 *  io.agentscope.core.tool.AgentTool
 *  io.agentscope.core.tool.Toolkit
 *  io.agentscope.core.tool.mcp.McpClientWrapper
 *  io.agentscope.extensions.model.openai.OpenAIChatModel
 *  io.agentscope.harness.agent.HarnessAgent
 *  io.agentscope.harness.agent.HarnessAgent$Builder
 *  org.slf4j.Logger
 *  org.slf4j.LoggerFactory
 */
package com.conca.agentkit;

import com.conca.agentkit.AgentDefinition;
import com.conca.agentkit.HttpToolAdapter;
import com.conca.agentkit.McpSupport;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import io.agentscope.harness.agent.workspace.LocalFsMode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AgentBootstrapper {
    private static final Logger log = LoggerFactory.getLogger(AgentBootstrapper.class);
    private static final int DEFAULT_MAX_ITERS = 6;
    private static final int MAX_ITERS_LIMIT = 20;
    private static final Duration MCP_CONNECT_TIMEOUT = Duration.ofSeconds(15L);
    private static final Duration MCP_INIT_TIMEOUT = Duration.ofSeconds(20L);
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");

    private AgentBootstrapper() {
    }

    public static BuiltAgent build(AgentDefinition def, Path workspace, Path dataRoot, AgentStateStore stateStore, String apiKey, List<Object> extraTools, List<MiddlewareBase> middlewares, Function<String, String> envLookup) throws IOException {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("\u6a21\u578b\u5bc6\u94a5\u672a\u914d\u7f6e\uff08\u5e73\u53f0\u5185\u4fdd\u5b58\u5728\u5bc6\u94a5\u5e93\uff0c\u72ec\u7acb\u8fd0\u884c\u9700\u8bbe\u7f6e\u73af\u5883\u53d8\u91cf AGENT_API_KEY\uff09");
        }
        if (dataRoot == null) {
            throw new IllegalStateException("数据根（dataRoot）未配置：智能体文件操作边界必填，禁止无边界装配");
        }
        if (def.model() == null || def.model().baseUrl().isBlank() || def.model().modelName().isBlank()) {
            throw new IllegalStateException("\u6a21\u578b\u914d\u7f6e\u4e0d\u5b8c\u6574\uff1a\u9700\u8981 baseUrl \u4e0e modelName");
        }
        AgentBootstrapper.prepareWorkspaceSkeleton(workspace);
        OpenAIChatModel chatModel = OpenAIChatModel.builder().baseUrl(AgentBootstrapper.resolve(def.model().baseUrl(), envLookup)).apiKey(apiKey).modelName(def.model().modelName()).stream(true).build();
        Toolkit toolkit = new Toolkit();
        for (Object object : extraTools) {
            toolkit.registerTool(object);
        }
        for (AgentDefinition.HttpToolConfig httpToolConfig : def.httpTools()) {
            AgentDefinition.HttpToolConfig resolved = new AgentDefinition.HttpToolConfig(httpToolConfig.name(), httpToolConfig.description(), AgentBootstrapper.resolve(httpToolConfig.endpoint(), envLookup), httpToolConfig.method(), httpToolConfig.pathPrefix());
            toolkit.registerAgentTool((AgentTool)new HttpToolAdapter(resolved));
        }
        ArrayList<McpClientWrapper> mcpClients = new ArrayList<McpClientWrapper>();
        for (AgentDefinition.McpConfig cfg : def.mcps()) {
            McpClientWrapper wrapper = McpSupport.register(toolkit, cfg, MCP_CONNECT_TIMEOUT, MCP_INIT_TIMEOUT);
            if (wrapper == null) continue;
            mcpClients.add(wrapper);
        }
        HarnessAgent.Builder builder = HarnessAgent.builder().name(def.id()).description(def.description().isBlank() ? def.name() : def.description()).sysPrompt(AgentBootstrapper.effectivePrompt(def)).model((Model)chatModel).workspace(workspace).filesystem(new LocalFilesystemSpec().project(dataRoot).projectWritable(true).mode(LocalFsMode.ROOTED)).stateStore(stateStore).maxIters(AgentBootstrapper.clampIters(def.maxIters())).toolkit(toolkit).middlewares(middlewares == null ? List.of() : middlewares).disableDynamicSubagents().disableDefaultWorkspaceSkills();
        HarnessAgent agent = builder.build();
        log.info("\u667a\u80fd\u4f53\u5df2\u88c5\u914d\uff1a{}\uff08tools={}, skills={}, subagents={}\uff09", new Object[]{def.id(), toolkit.getToolNames(), def.skills(), def.subagents().size()});
        return new BuiltAgent(agent, mcpClients);
    }

    private static String effectivePrompt(AgentDefinition def) {
        if (!def.sysPrompt().isBlank()) {
            return def.sysPrompt();
        }
        return "\u4f60\u662f\u300c" + def.name() + "\u300d\u3002\u8bf7\u7528\u4e2d\u6587\u56de\u7b54\u7528\u6237\u95ee\u9898\uff0c\u56de\u7b54\u9700\u7b80\u6d01\u3001\u51c6\u786e\u3001\u6709\u6761\u7406\u3002\u5f53\u95ee\u9898\u6d89\u53ca\u4e8b\u5b9e\u4fe1\u606f\u800c\u4e0d\u786e\u5b9a\u65f6\uff0c\u5982\u5b9e\u8bf4\u660e\uff0c\u4e0d\u8981\u7f16\u9020\u3002";
    }

    private static void prepareWorkspaceSkeleton(Path workspace) throws IOException {
        Files.createDirectories(workspace.resolve("skills"), new FileAttribute[0]);
        Files.createDirectories(workspace.resolve("subagents"), new FileAttribute[0]);
    }

    private static int clampIters(int maxIters) {
        if (maxIters <= 0) {
            return 6;
        }
        return Math.min(maxIters, 20);
    }

    private static String resolve(String value, Function<String, String> envLookup) {
        if (envLookup == null || value == null || value.indexOf("${") < 0) {
            return value;
        }
        Matcher m = PLACEHOLDER.matcher(value);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String v = envLookup.apply(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(v == null ? m.group() : v));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    public record BuiltAgent(HarnessAgent agent, List<McpClientWrapper> mcpClients) {
        public void close() {
            try {
                this.agent.close();
            }
            catch (Exception exception) {
                // empty catch block
            }
            this.mcpClients.forEach(McpSupport::closeQuietly);
        }
    }
}

