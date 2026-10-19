package com.conca.ontology.agent.service;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.conca.ontology.agent.service.RewriteConfigService;
import com.conca.ontology.shared.db.AgentDistributedStores;
import com.conca.ontology.shared.db.MySqlAgentStateStores;

import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec;

import jakarta.annotation.PreDestroy;

/**
 * 语义改写专用 agent 持有者（仿 JudgeAgentHolder）：
 * 每项功能（semantic/typo/term/context）一个独立 HarnessAgent（纯 LLM 管道，
 * 无工具/技能/子代理），sysPrompt = 功能提示词 + 输出格式提示词（页面可改，热生效）。
 * 聊天接入见 AgentChatService：主 agent 启动前先跑改写轮（过程模拟 thinking 推流）。
 *
 * agent 按（type, sysPrompt）懒构建缓存：提示词被修改保存后，
 * 首次调用按新 sysPrompt 重建（旧 agent 关闭，进行中调用降级放行原文，不阻断聊天）。
 * 每次改写独立 sessionId（userId=rewrite-<type>），会话留痕落 MySQL（agent_state_rewrite 表），
 * 供回查每次改写的完整 prompt 与输出；AgentTraceMiddleware 输出调用日志。
 * 工作区存储双模式（app.agent.distributed-store.enabled，四链统一开关）：
 * true=RemoteFilesystemSpec + 分布式存储（agent_state_rewrite + agent_kv_rewrite，多副本共享），
 * false=本机模式（仅显式状态表留痕）。两模式状态同表，切换零迁移。
 *
 * 刻意不注册为 Spring bean：避免与主问答 agent（AgentScopeConfig#harnessAgent）
 * 形成 HarnessAgent 双候选。
 *
 * @author tianbaoxing
 * @date 2026-09-11
 */
@Component
public class RewriteAgentHolder {

    private static final Logger log = LoggerFactory.getLogger(RewriteAgentHolder.class);

    @Value("${app.agent.base-url:}")
    private String baseUrl;

    @Value("${app.agent.api-key:}")
    private String apiKey;

    @Value("${app.agent.model:}")
    private String model;

    @Value("${app.rewrite.workspace-dir:data/rewrite-workspace}")
    private String workspaceDir;

    @org.springframework.beans.factory.annotation.Autowired
    private javax.sql.DataSource dataSource;

    @Value("${app.rewrite.timeout-seconds:30}")
    private int timeoutSeconds;

    @Value("${app.rewrite.max-output-tokens:1000}")
    private int maxOutputTokens;

    /** 工作区共享存储全局开关（四链统一，与主链 AgentScopeConfig 同一配置项） */
    @Value("${app.agent.distributed-store.enabled:false}")
    private boolean distributedStoreEnabled;

    /** 本链分布式存储（四类型改写 agent 共享一份，避免重建时重复跑建表 DDL）；仅 synchronized agent() 内访问 */
    private DistributedStore distributedStore;

    private final Map<String, CachedAgent> cache = new HashMap<>();

    private record CachedAgent(String sysPrompt, HarnessAgent agent) {
    }

    /** 是否具备构建条件（key 未配置时改写不可用） */
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * 取某项功能的改写 agent：同 sysPrompt 命中缓存；提示词变化（被修改保存）则重建。
     *
     * @param type      功能类型（RewriteConfigService 常量）
     * @param sysPrompt 功能提示词 + 输出格式提示词（构建缓存键）
     */
    public synchronized HarnessAgent agent(String type, String sysPrompt) {
        CachedAgent cached = cache.get(type);
        if (cached != null && cached.sysPrompt().equals(sysPrompt)) {
            return cached.agent();
        }
        HarnessAgent built = build(type, sysPrompt);
        cache.put(type, new CachedAgent(sysPrompt, built));
        if (cached != null) {
            closeQuietly(cached.agent());
        }
        return built;
    }

    private HarnessAgent build(String type, String sysPrompt) {
        try {
            Files.createDirectories(Path.of(workspaceDir));
            OpenAIChatModel chatModel = OpenAIChatModel.builder()
                    .baseUrl(baseUrl)
                    .apiKey(apiKey)
                    .modelName(model)
                    .stream(false)
                    .generateOptions(io.agentscope.core.model.GenerateOptions.builder()
                            .temperature(0.0)
                            .maxTokens(maxOutputTokens)
                            .build())
                    .build();
            HarnessAgent.Builder builder = HarnessAgent.builder()
                    .name("rewrite-" + type)
                    .description("语义改写专用（纯 LLM 管道，无工具/技能/子代理）："
                            + RewriteConfigService.FEATURE_LABELS.getOrDefault(type, type))
                    .sysPrompt(sysPrompt)
                    .model(chatModel)
                    .workspace(Path.of(workspaceDir));
            if (distributedStoreEnabled) {
                // 工作区共享存储（四链统一架构，与主链同构）：状态表 agent_state_rewrite 零迁移，
                // KV 表 agent_kv_rewrite；USER 级隔离，KV 命名空间按各类型合成用户（rewrite-<type>）区分
                if (distributedStore == null) {
                    distributedStore = AgentDistributedStores.create(dataSource,
                            MySqlAgentStateStores.REWRITE, AgentDistributedStores.KV_REWRITE);
                }
                builder.distributedStore(distributedStore)
                        .filesystem(new RemoteFilesystemSpec().isolationScope(IsolationScope.USER));
            } else {
                // 本机模式（现状）：显式状态表留痕
                builder.stateStore(MySqlAgentStateStores.create(dataSource, MySqlAgentStateStores.REWRITE));
            }
            HarnessAgent agent = builder
                    .maxIters(1)
                    .checkRunning(false)
                    .modelExecutionConfig(ExecutionConfig.builder()
                            .timeout(Duration.ofSeconds(timeoutSeconds))
                            .maxAttempts(1)
                            .build())
                    .enableAgentTracingLog(true)
                    // 最小开放面：改写是纯 LLM 任务，禁用全部执行能力
                    .disableShellTool()
                    .disableFilesystemTools()
                    .disableMemoryTools()
                    .disableMemoryHooks()
                    .disableSubagents()
                    .disableDynamicSubagents()
                    .disableDynamicSkills()
                    .disableDefaultWorkspaceSkills()
                    .disableToolsConfig()
                    .disableCompaction()
                    .disableToolResultEviction()
                    .disableWorkspaceContext()
                    .disableAtPathExpansion()
                    .build();
            log.info("rewrite agent 已构建：rewrite-{}（model={}）", type, model);
            return agent;
        } catch (Exception e) {
            throw new IllegalStateException("rewrite agent 构建失败（type=" + type + "）：" + e.getMessage(), e);
        }
    }

    /** 每次改写独立会话 id（留痕回查粒度 = 单次改写） */
    public static String nextSessionId(String type) {
        return "rewrite-" + type + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @PreDestroy
    void shutdown() {
        cache.values().forEach(c -> closeQuietly(c.agent()));
        cache.clear();
    }

    private static void closeQuietly(HarnessAgent agent) {
        try {
            agent.close();
        } catch (Exception e) {
            log.warn("rewrite agent 关闭异常：{}", e.getMessage());
        }
    }
}
