package com.conca.ontology.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.conca.ontology.exception.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;

/**
 * 记忆工具热插拔开关：运行时控制 HarnessAgent 内置的
 * session_search/memory_search / memory_get / memory_save 三个工具是否对模型可见。
 *
 * 机制（方案 A：Toolkit 热插拔）：
 * - 启动时（ApplicationReadyEvent，单例 agent 已装配）先抓取三个工具的原始引用缓存，
 *   再按持久化状态决定是否移除——移除后 getTool 返回 null，无缓存则无法回装；
 * - 关 = removeToolIfSame(name, 缓存引用)：仅当 Toolkit 中仍是原实例才移除，
 *   防误删运行期被框架替换的同名工具；
 * - 开 = registerAgentTool(缓存引用)（已在位则跳过，防重复注册）；
 * - 状态持久化到 data/agent-memory-toggle.json，重启保持关闭态；
 *   文件缺失/损坏/写失败一律回退默认开，不阻断切换与主流程。
 *
 * 边界：
 * - 只控制工具注册，不动 MemoryFlush/Maintenance 中间件（记忆自动沉淀照常运行）；
 * - 生效时机为下一次模型请求的工具 schema；进行中的流式轮次 schema 已生成，
 *   模型仍可能调用 memory_* → 工具不存在 → 收到错误结果自行降级，属预期行为。
 */
@Component
public class AgentMemoryToolsToggle {

    private static final Logger log = LoggerFactory.getLogger(AgentMemoryToolsToggle.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** harness 内置记忆工具（三者一起开关，语义才是完整的"记忆能力"） */
    private static final List<String> MEMORY_TOOLS = List.of("memory_search", "memory_get", "memory_save");
    private static final Path STATE_FILE = Path.of("data", "agent-memory-toggle.json");

    private final ObjectProvider<HarnessAgent> agentProvider;

    /** 原始工具引用：必须在首次移除前抓取，回装依赖它 */
    private final Map<String, AgentTool> cachedTools = new LinkedHashMap<>();

    private volatile boolean enabled = true;

    public AgentMemoryToolsToggle(ObjectProvider<HarnessAgent> agentProvider) {
        this.agentProvider = agentProvider;
    }

    /** agent 装配完成后抓引用并应用持久化状态；未配置 key 时无工具可管，静默跳过 */
    @EventListener(ApplicationReadyEvent.class)
    public synchronized void init() {
        HarnessAgent agent = agentProvider.getIfAvailable();
        if (agent == null) {
            return;
        }
        Toolkit toolkit = agent.getToolkit();
        for (String name : MEMORY_TOOLS) {
            AgentTool tool = toolkit.getTool(name);
            if (tool != null) {
                cachedTools.put(name, tool);
            } else {
                log.warn("记忆工具 {} 未在 Toolkit 中注册，跳过管理", name);
            }
        }
        enabled = readState();
        if (!enabled) {
            removeTools(toolkit);
        }
        log.info("记忆工具开关初始化：enabled={}，托管工具 {} 个", enabled, cachedTools.size());
    }

    /**
     * 切换开关（幂等），返回切换后的状态快照。
     * agent 未装配时抛 409（与技能接口的降级语义区分：查询可降级，变更不可）。
     */
    public synchronized Map<String, Object> setEnabled(boolean target) {
        HarnessAgent agent = agentProvider.getIfAvailable();
        if (agent == null) {
            throw new ApiException(409, "AI 服务未配置（缺少 OPENROUTER_API_KEY），无法切换");
        }
        if (target != enabled) {
            Toolkit toolkit = agent.getToolkit();
            if (target) {
                registerTools(toolkit);
            } else {
                removeTools(toolkit);
            }
            enabled = target;
            writeState(target);
        }
        return status();
    }

    /** 状态快照：activeTools 为 Toolkit 实际存活集，供前端与开关值对账（以实际为准） */
    public Map<String, Object> status() {
        List<String> active = new ArrayList<>();
        HarnessAgent agent = agentProvider.getIfAvailable();
        if (agent != null) {
            Toolkit toolkit = agent.getToolkit();
            for (String name : MEMORY_TOOLS) {
                if (toolkit.getTool(name) != null) {
                    active.add(name);
                }
            }
        }
        return Map.of(
                "enabled", enabled,
                "agentAvailable", agent != null,
                "activeTools", active);
    }

    private void removeTools(Toolkit toolkit) {
        for (Map.Entry<String, AgentTool> e : cachedTools.entrySet()) {
            toolkit.removeToolIfSame(e.getKey(), e.getValue());
        }
    }

    private void registerTools(Toolkit toolkit) {
        for (Map.Entry<String, AgentTool> e : cachedTools.entrySet()) {
            if (toolkit.getTool(e.getKey()) == null) {
                toolkit.registerAgentTool(e.getValue());
            }
        }
    }

    private boolean readState() {
        try {
            if (Files.exists(STATE_FILE)) {
                return MAPPER.readTree(Files.readString(STATE_FILE)).path("enabled").asBoolean(true);
            }
        } catch (Exception e) {
            log.warn("读取记忆工具开关状态失败，按默认开启处理：{}", e.getMessage());
        }
        return true;
    }

    private void writeState(boolean value) {
        try {
            Files.createDirectories(STATE_FILE.getParent());
            Files.writeString(STATE_FILE, "{\"enabled\": " + value + "}");
        } catch (IOException e) {
            log.warn("记忆工具开关状态写入失败（不影响本次切换）：{}", e.getMessage());
        }
    }
}
