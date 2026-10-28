package com.conca.ontology.builder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.conca.agentkit.AgentBootstrapper;
import com.conca.agentkit.AgentDefinition;
import com.conca.ontology.agent.middleware.ContentSecurityMiddleware;
import com.conca.ontology.agent.middleware.PathBoundaryMiddleware;
import com.conca.ontology.agent.middleware.RolePermissionMiddleware;
import com.conca.ontology.agent.tool.ChartPluginTool;
import com.conca.ontology.agent.tool.CurrentTimeTool;
import com.conca.ontology.agent.tool.GetLocationTool;
import com.conca.ontology.agent.tool.HttpProxyTool;
import com.conca.ontology.agent.tool.SystemResourceTool;
import com.conca.ontology.agent.service.GeoLocationService;
import com.conca.ontology.builder.service.AgentBuilderService;
import com.conca.ontology.builder.service.AgentWorkspaceService;
import com.conca.ontology.builder.store.AgentDefinitionStore;
import com.conca.ontology.builder.tool.AgentBuilderTool;
import com.conca.ontology.content.service.ContentSecurityService;
import com.conca.ontology.disjoint.service.DisjointRuleService;
import com.conca.ontology.disjoint.tool.DisjointRulesTool;
import com.conca.ontology.shared.db.MySqlAgentStateStores;
import com.conca.ontology.shared.web.ApiException;
import com.conca.ontology.user.service.RolePermissionService;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import jakarta.annotation.PreDestroy;

/**
 * 自建智能体实例注册表（多实例工厂，替代主智能体"启动时单例装配"的模式）。
 *
 * - 惰性构建：首次对话时按定义装配，构建按 agentId 互斥（避免并发建双实例）
 * - 空闲驱逐：默认 30 分钟无访问且无进行中流 → close 释放模型连接与内存；
 *   定义更新/删除立即驱逐，下次访问重建生效
 * - 生命周期：BuiltAgent 聚合 agent 与 MCP 连接统一 close（agent.close() 不保证关 MCP）
 *
 * @author tianbaoxing
 * @date 2026-09-24
 */
@Service
public class AgentRegistry {

    private static final Logger log = LoggerFactory.getLogger(AgentRegistry.class);

    /** 模型密钥环境变量名（不落盘、不进定义，服务启动前设置） */
    private static final String ENV_API_KEY = "AGENT_API_KEY";

    /** 内置工具白名单（向导勾选面；实例按需新建，全部为无状态工具） */
    public static final Map<String, String> BUILTIN_TOOLS = Map.of(
            "graph-query", "图谱读写管道（http_request_ontology_query，/api/llm/ 白名单，写方法需管理员会话），配合 ontology-query / ontology-builder 技能使用",
            "chart-plugin", "图表插件（对话中渲染图表）",
            "disjoint-rules", "互斥约束词典查询（本体类不相交规则，抑制幻觉）",
            "agent-builder", "智能体搭建管道（agent_builder，创建/编辑/删除自定义智能体，变更字段合并语义）",
            "current_time", "当前日期时间查询（服务端时钟，含星期与时区，抑制时间幻觉）",
            "get_location", "访问者位置查询（客户端 IP 定位，城市级，GeoLite2 本地查询，抑制位置幻觉）");

    private static final class Entry {
        final AgentBootstrapper.BuiltAgent built;
        final AtomicInteger inUse = new AtomicInteger();
        final AtomicLong lastAccess = new AtomicLong(System.currentTimeMillis());

        Entry(AgentBootstrapper.BuiltAgent built) {
            this.built = built;
        }
    }

    private final AgentDefinitionStore store;
    private final AgentWorkspaceService workspaceService;
    private final ContentSecurityService contentSecurityService;
    private final DisjointRuleService disjointRuleService;
    /**
     * AgentBuilderService 经 ObjectProvider 延迟解析：其构造器已注入本注册表，
     * 直接构造注入会形成循环依赖（Spring Boot 2.6+ 默认禁止）；
     * 实例装配（builtinTools）发生在首次对话时，届时容器早已就绪。
     */
    private final ObjectProvider<AgentBuilderService> builderServiceProvider;
    private final String llmBaseUrl;
    private final javax.sql.DataSource dataSource;
    private final RolePermissionService rolePermissionService;
    private final GeoLocationService geoLocationService;
    private final String dataRootCfg;

    private final Map<String, Entry> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();
    private final long idleMillis;
    private final ScheduledExecutorService sweeper;

    public AgentRegistry(AgentDefinitionStore store,
            AgentWorkspaceService workspaceService, ContentSecurityService contentSecurityService,
            DisjointRuleService disjointRuleService,
            ObjectProvider<AgentBuilderService> builderServiceProvider,
            @Qualifier("llmBaseUrl") String llmBaseUrl,
            javax.sql.DataSource dataSource,
            RolePermissionService rolePermissionService,
            GeoLocationService geoLocationService,
            @Value("${app.agent.data-root:data}") String dataRootCfg,
            @Value("${app.agents.idle-minutes:30}") int idleMinutes) {
        this.store = store;
        this.workspaceService = workspaceService;
        this.contentSecurityService = contentSecurityService;
        this.disjointRuleService = disjointRuleService;
        this.builderServiceProvider = builderServiceProvider;
        this.llmBaseUrl = llmBaseUrl;
        this.dataSource = dataSource;
        this.rolePermissionService = rolePermissionService;
        this.geoLocationService = geoLocationService;
        this.dataRootCfg = dataRootCfg;
        this.idleMillis = Math.max(1, idleMinutes) * 60_000L;
        this.sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "agent-registry-sweeper");
            t.setDaemon(true);
            return t;
        });
        // 每 5 分钟清点一次空闲实例
        this.sweeper.scheduleWithFixedDelay(this::sweepIdle, 5, 5, TimeUnit.MINUTES);
    }

    /** 取可用实例（无则构建）；调用方必须在流结束后 release（空闲驱逐的进行中保护） */
    public HarnessAgent acquire(String agentId) {
        Entry entry = cache.get(agentId);
        if (entry == null) {
            synchronized (lockFor(agentId)) {
                entry = cache.get(agentId);
                if (entry == null) {
                    entry = new Entry(build(agentId));
                    cache.put(agentId, entry);
                }
            }
        }
        entry.inUse.incrementAndGet();
        entry.lastAccess.set(System.currentTimeMillis());
        return entry.built.agent();
    }

    /** 归还（chat 流 doFinally 调用） */
    public void release(String agentId) {
        Entry entry = cache.get(agentId);
        if (entry != null) {
            entry.inUse.decrementAndGet();
            entry.lastAccess.set(System.currentTimeMillis());
        }
    }

    /** 立即驱逐（定义更新/删除时）：close agent 与 MCP 连接 */
    public void evict(String agentId) {
        Entry entry = cache.remove(agentId);
        if (entry != null) {
            closeQuietly(entry);
            log.info("智能体实例已驱逐：{}", agentId);
        }
    }

    @PreDestroy
    public void shutdown() {
        sweeper.shutdownNow();
        cache.keySet().forEach(this::evict);
    }

    /** 是否存在已装配实例（诊断用） */
    public boolean isLive(String agentId) {
        return cache.containsKey(agentId);
    }

    /**
     * 轻量会话存储（不构建实例、零网络副作用）：自建智能体的会话列表/历史/删除/重命名。
     * 与装配实例共用同一 MySQL 会话表（agent_state_{agentId}，官方 MysqlAgentStateStore），读写一致。
     */
    public AgentStateStore stateStoreOf(String agentId) {
        store.require(agentId);
        return MySqlAgentStateStores.create(dataSource, agentId);
    }

    // ---------------- 内部 ----------------

    /** 环境变量 AGENT_API_KEY 是否已配置（导出前置校验用） */
    public static boolean envApiKeyPresent() {
        String key = System.getenv(ENV_API_KEY);
        return key != null && !key.isBlank();
    }

    /** 模型密钥统一经环境变量 AGENT_API_KEY 注入（不落盘、不进定义）；未设置给确定性报错 */
    public static String requireEnvApiKey() {
        String key = System.getenv(ENV_API_KEY);
        if (key == null || key.isBlank()) {
            throw new ApiException(503, "模型密钥未配置：请设置环境变量 AGENT_API_KEY"
                    + "（如 set AGENT_API_KEY=sk-xxxx你的密钥xxxx）后重启服务");
        }
        return key.strip();
    }

    private AgentBootstrapper.BuiltAgent build(String agentId) {
        AgentDefinition def = store.require(agentId);
        String apiKey = requireEnvApiKey();
        try {
            Path workspace = workspaceService.workspaceOf(agentId);
            // 防线一/二共用的数据根（构造时校验工作区在数据根内，fail-fast）
            PathBoundaryMiddleware boundary = new PathBoundaryMiddleware(dataRootCfg, workspace);
            AgentBootstrapper.BuiltAgent built = AgentBootstrapper.build(
                    def, workspace, boundary.dataRoot(), MySqlAgentStateStores.create(dataSource, agentId),
                    apiKey, builtinTools(def),
                    List.of(new ContentSecurityMiddleware(contentSecurityService),
                            new RolePermissionMiddleware(rolePermissionService),
                            boundary),
                    null);
            log.info("智能体实例已构建：{}", agentId);
            return built;
        } catch (IllegalStateException e) {
            throw new ApiException(503, e.getMessage());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 定义勾选的白名单内置工具 → 平台工具实例（数据化 httpTools/mcps 由 Bootstrapper 装配） */
    private List<Object> builtinTools(AgentDefinition def) {
        List<Object> tools = new ArrayList<>();
        for (String name : def.tools()) {
            switch (name) {
                case "graph-query" -> tools.add(new HttpProxyTool(llmBaseUrl));
                case "chart-plugin" -> tools.add(new ChartPluginTool());
                case "disjoint-rules" -> tools.add(new DisjointRulesTool(disjointRuleService));
                case "agent-builder" -> tools.add(new AgentBuilderTool(builderServiceProvider.getObject()));
                case "current_time" -> tools.add(new CurrentTimeTool());
                case "get_location" -> tools.add(new GetLocationTool(geoLocationService));
                case "system-resources" -> tools.add(new SystemResourceTool());
                default -> throw new ApiException(400, "内置工具不在白名单：" + name);
            }
        }
        return tools;
    }

    private void sweepIdle() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Entry> e : cache.entrySet()) {
            Entry entry = e.getValue();
            if (entry.inUse.get() == 0 && now - entry.lastAccess.get() > idleMillis) {
                if (cache.remove(e.getKey(), entry)) {
                    closeQuietly(entry);
                    log.info("智能体实例空闲驱逐：{}", e.getKey());
                }
            }
        }
    }

    private static void closeQuietly(Entry entry) {
        try {
            entry.built.close();
        } catch (Exception e) {
            log.warn("智能体实例关闭异常：{}", e.getMessage());
        }
    }

    private Object lockFor(String agentId) {
        return locks.computeIfAbsent(agentId, k -> new Object());
    }
}
