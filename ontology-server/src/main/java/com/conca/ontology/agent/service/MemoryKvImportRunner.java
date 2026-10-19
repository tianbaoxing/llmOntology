package com.conca.ontology.agent.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import com.conca.ontology.user.service.UserService;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.workspace.WorkspaceManager;

/**
 * 记忆 KV 一次性导入工具（本机 → 共享存储切换）：app.agent.workspace-kv-import.enabled=true 时，
 * 启动后遍历本地 workspace 各用户目录，将 MEMORY.md 与 memory/yyyy-MM-dd.md 经 WorkspaceManager
 * 写入分布式存储（USER 级命名空间路由由框架处理）。
 *
 * - 一次性语义：完成即在 workspace 根写 .kv-imported 标记，后续启动自动跳过；
 *   仅用于"首次从本机模式切共享存储"的那一次启动，导入完成后请将开关改回 false。
 * - 防覆盖：标记存在即跳过——严禁在共享存储已积累新记忆后重开此开关（会以旧本地文件覆盖 KV）。
 * - 会话状态无需导入：agent_state_main 表本机/共享两模式同款（收编零迁移）。
 * - 非用户目录（.skills-cache、skills 种子）与白名单外文件不导入；本地原文件保留为种子兜底，
 *   确认 KV 正常后可手工归档。
 * - 写入失败（KV 不可达）异常上抛阻断启动——宁可启动失败也不静默丢记忆。
 *
 * @author tianbaoxing
 * @date 2026-10-19
 */
@Component
public class MemoryKvImportRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MemoryKvImportRunner.class);

    /** 每日记忆文件名日期格式（与 AgentMemoryService 白名单一致） */
    private static final Pattern DAILY_NAME = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}\\.md$");
    /** 导入完成标记（workspace 根下） */
    private static final String MARKER_NAME = ".kv-imported";
    /** 导入会话占位：记忆为用户层数据，USER 级隔离下不影响命名空间路由 */
    private static final String IMPORT_SESSION = "kv-import";

    private final ObjectProvider<HarnessAgent> agentProvider;
    private final UserService users;

    @Value("${app.agent.workspace-dir:data/agent-workspace}")
    private String workspaceDir;

    @Value("${app.agent.distributed-store.enabled:false}")
    private boolean distributedStoreEnabled;

    @Value("${app.agent.workspace-kv-import.enabled:false}")
    private boolean importEnabled;

    public MemoryKvImportRunner(ObjectProvider<HarnessAgent> agentProvider, UserService users) {
        this.agentProvider = agentProvider;
        this.users = users;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!importEnabled) {
            return;
        }
        if (!distributedStoreEnabled) {
            log.warn("[记忆导入] workspace-kv-import.enabled=true 需配合 distributed-store.enabled=true，本次跳过");
            return;
        }
        Path workspace = Path.of(workspaceDir);
        Path marker = workspace.resolve(MARKER_NAME);
        if (Files.exists(marker)) {
            log.info("[记忆导入] 已完成过导入（{}），跳过", marker);
            return;
        }
        HarnessAgent agent = agentProvider.getIfAvailable();
        if (agent == null) {
            log.warn("[记忆导入] 主智能体未装配（api-key 未配置），跳过导入");
            return;
        }

        int userCount = 0;
        int fileCount = 0;
        if (Files.isDirectory(workspace)) {
            List<Path> userDirs;
            try (Stream<Path> s = Files.list(workspace)) {
                userDirs = s.filter(Files::isDirectory).toList();
            }
            for (Path userDir : userDirs) {
                String userId = userDir.getFileName().toString();
                if (userId.startsWith(".") || "skills".equals(userId) || !users.exists(userId)) {
                    continue;
                }
                int imported = importUser(agent, userId);
                if (imported > 0) {
                    userCount++;
                    fileCount += imported;
                }
            }
        }
        Files.writeString(marker, "imported at " + Instant.now() + "\n", StandardCharsets.UTF_8);
        log.info("[记忆导入] 完成：用户 {} 个、文件 {} 个（标记 {}）", userCount, fileCount, marker);
    }

    /** 单用户导入：MEMORY.md + memory/ 下日期文件；返回导入文件数 */
    private int importUser(HarnessAgent agent, String userId) throws IOException {
        Path userDir = Path.of(workspaceDir, userId);
        List<String[]> toImport = new ArrayList<>();
        Path curated = userDir.resolve("MEMORY.md");
        if (Files.isRegularFile(curated)) {
            toImport.add(new String[] {"MEMORY.md", curated.toString()});
        }
        Path memoryDir = userDir.resolve("memory");
        if (Files.isDirectory(memoryDir)) {
            List<Path> files;
            try (Stream<Path> s = Files.list(memoryDir)) {
                files = s.filter(Files::isRegularFile).toList();
            }
            for (Path f : files) {
                String name = f.getFileName().toString();
                if (DAILY_NAME.matcher(name).matches()) {
                    toImport.add(new String[] {"memory/" + name, f.toString()});
                }
            }
        }
        if (toImport.isEmpty()) {
            return 0;
        }
        WorkspaceManager wm = agent.workspaceFor(userId, IMPORT_SESSION);
        RuntimeContext rc = RuntimeContext.builder().userId(userId).sessionId(IMPORT_SESSION).build();
        for (String[] entry : toImport) {
            String content = Files.readString(Path.of(entry[1]), StandardCharsets.UTF_8);
            wm.writeUtf8WorkspaceRelative(rc, entry[0], content);
        }
        log.info("[记忆导入] 用户 {}：{} 个文件（{}）", userId, toImport.size(),
                toImport.stream().map(e -> e[0]).toList());
        return toImport.size();
    }
}
