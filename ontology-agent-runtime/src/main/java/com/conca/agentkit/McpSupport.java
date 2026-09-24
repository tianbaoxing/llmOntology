/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  io.agentscope.core.tool.Toolkit
 *  io.agentscope.core.tool.mcp.McpClientBuilder
 *  io.agentscope.core.tool.mcp.McpClientWrapper
 *  org.slf4j.Logger
 *  org.slf4j.LoggerFactory
 */
package com.conca.agentkit;

import com.conca.agentkit.AgentDefinition;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class McpSupport {
    private static final Logger log = LoggerFactory.getLogger(McpSupport.class);

    private McpSupport() {
    }

    public static McpClientWrapper register(Toolkit toolkit, AgentDefinition.McpConfig cfg, Duration connectTimeout, Duration initTimeout) {
        String name;
        String string = name = cfg.name().isBlank() ? "mcp-" + Integer.toHexString(cfg.url().hashCode()) : cfg.name();
        if ("sse".equalsIgnoreCase(cfg.transport())) {
            return McpSupport.tryRegister(toolkit, name, McpSupport.sseBase(cfg.url()), false, connectTimeout, initTimeout);
        }
        McpClientWrapper streamable = McpSupport.tryRegister(toolkit, name, cfg.url(), true, connectTimeout, initTimeout);
        if (streamable != null) {
            return streamable;
        }
        return McpSupport.tryRegister(toolkit, name, McpSupport.sseBase(cfg.url()), false, connectTimeout, initTimeout);
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    public static List<String> probe(String url, Duration connectTimeout, Duration initTimeout) {
        Toolkit probe = new Toolkit();
        McpClientWrapper wrapper = McpSupport.register(probe, new AgentDefinition.McpConfig("probe", url, ""), connectTimeout, initTimeout);
        if (wrapper == null) {
            throw new IllegalStateException("MCP \u7aef\u70b9\u63e1\u624b\u5931\u8d25\uff1a" + url);
        }
        try {
            List<String> list = probe.getToolNames().stream().sorted().toList();
            return list;
        }
        finally {
            McpSupport.closeQuietly(wrapper);
        }
    }

    private static McpClientWrapper tryRegister(Toolkit toolkit, String name, String url, boolean streamable, Duration connectTimeout, Duration initTimeout) {
        McpClientWrapper wrapper = null;
        try {
            McpClientBuilder builder = McpClientBuilder.create((String)name).timeout(connectTimeout);
            if (streamable) {
                builder.streamableHttpTransport(url);
            } else {
                builder.sseTransport(url);
            }
            wrapper = builder.buildSync();
            toolkit.registerMcpClient(wrapper).block(initTimeout);
            log.info("MCP \u5ba2\u6237\u7aef [{}] \u5df2\u6ce8\u518c\uff08transport={}\uff0curl={}\uff09", new Object[]{name, streamable ? "streamable" : "sse", url});
            return wrapper;
        }
        catch (Exception e) {
            log.warn("MCP \u5ba2\u6237\u7aef [{}] \u6ce8\u518c\u5931\u8d25\uff08transport={}\uff0curl={}\uff09\uff1a{}", new Object[]{name, streamable ? "streamable" : "sse", url, e.getMessage()});
            if (wrapper != null) {
                McpSupport.closeQuietly(wrapper);
            }
            return null;
        }
    }

    private static String sseBase(String url) {
        return url.replaceAll("(?i)/mcp/?$", "");
    }

    public static void closeQuietly(McpClientWrapper wrapper) {
        if (wrapper == null) {
            return;
        }
        try {
            wrapper.close();
        }
        catch (Exception exception) {
            // empty catch block
        }
    }
}

