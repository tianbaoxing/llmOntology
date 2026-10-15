package com.conca.ontology.builder.service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.conca.agentkit.AgentDefinition;
import com.conca.agentkit.AgentPackageCodec;
import com.conca.agentkit.McpSupport;
import com.conca.ontology.builder.AgentRegistry;
import com.conca.ontology.builder.dto.request.AgentSaveRequest;
import com.conca.ontology.builder.model.AgentDefinitionStore;
import com.conca.ontology.shared.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 智能体搭建编排：校验 → 落定义/工作区 → 维护注册表；导出 zip / 导出二次开发工程。
 * 校验顺序：本地静态校验（[参数错误] 层）→ 连通性测试（test-model/test-mcp，可选）→ 保存。
 * 模型密钥不落盘：统一经环境变量 AGENT_API_KEY 注入（AgentRegistry.requireEnvApiKey）。
 *
 * @author tianbaoxing
 * @date 2026-09-24
 */
@Service
public class AgentBuilderService {

    private static final Pattern TOOL_NAME = Pattern.compile("^[a-z][a-z0-9_]{0,63}$");
    private static final Pattern HTTP_URL = Pattern.compile("^https?://.+$");
    /** 导出工程名：仅英文字母（Maven artifactId 兼容），留空回退 agent-<id>-project */
    private static final Pattern PROJECT_NAME = Pattern.compile("^[A-Za-z]{1,50}$");
    /** Java 包名：小写段式（com.example.agent），留空回退 com.agentexport */
    private static final Pattern JAVA_PACKAGE = Pattern.compile("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)*$");
    private static final int MAX_HTTP_TOOLS = 10;
    private static final int MAX_MCPS = 10;
    private static final int MAX_SUBAGENTS = 3;

    private final AgentDefinitionStore store;
    private final AgentWorkspaceService workspaceService;
    private final AgentRegistry registry;
    private final AgentProjectExportService projectExporter;
    private final ObjectMapper mapper;

    private final String exportedBy;
    private final String minHarnessVersion;
    private final SecureRandom random = new SecureRandom();

    public AgentBuilderService(AgentDefinitionStore store,
            AgentWorkspaceService workspaceService, AgentRegistry registry,
            AgentProjectExportService projectExporter, ObjectMapper mapper,
            @Value("${app.title:图谱}") String appTitle,
            @Value("${app.agents.min-harness-version:2.0.3}") String minHarnessVersion) {
        this.store = store;
        this.workspaceService = workspaceService;
        this.registry = registry;
        this.projectExporter = projectExporter;
        this.mapper = mapper;
        this.exportedBy = appTitle + "（llmOntology）";
        this.minHarnessVersion = minHarnessVersion;
    }

    // ---------------- CRUD ----------------

    /** 创建（id 自动生成，与目录/agent 名绑定后不可变） */
    public Map<String, Object> create(AgentSaveRequest req, String owner) {
        validate(req);
        String id = "agent-" + randomHex(8);
        while (store.exists(id)) {
            id = "agent-" + randomHex(8);
        }
        String now = now();
        AgentDefinition def = new AgentDefinition(id, req.name(), req.description(), req.sysPrompt(),
                req.greeting(), new AgentDefinition.ModelConfig(req.baseUrl(), req.modelName(), ""),
                req.maxIters() == null ? 0 : req.maxIters(), req.skills(), req.tools(),
                req.httpTools(), req.mcps(), req.subagents(),
                req.projectName(), req.packageName(),
                normalizeStatus(req.status()), owner, 1, minHarnessVersion, now, now());
        store.save(def);
        workspaceService.prepare(def);
        return detail(def);
    }

    /** 编辑（version+1 驱使注册表重建） */
    public Map<String, Object> update(String id, AgentSaveRequest req) {
        AgentDefinition old = store.require(id);
        validate(req);
        AgentDefinition def = new AgentDefinition(old.id(), req.name(), req.description(), req.sysPrompt(),
                req.greeting(), new AgentDefinition.ModelConfig(req.baseUrl(), req.modelName(), ""),
                req.maxIters() == null ? 0 : req.maxIters(), req.skills(), req.tools(),
                req.httpTools(), req.mcps(), req.subagents(),
                req.projectName(), req.packageName(),
                normalizeStatus(req.status()), old.owner(), old.version() + 1,
                minHarnessVersion, old.createdAt(), now());
        store.save(def);
        workspaceService.prepare(def);
        registry.evict(id);
        // 试运行语义：test 期间的会话均为临时试运行，编辑/发布后智能体从零开始；
        // active 期间的真实用户会话在后续编辑中保留（不打断在线对话记忆）
        if (AgentDefinition.STATUS_TEST.equals(old.status())) {
            workspaceService.clearState(id);
        }
        return detail(def);
    }

    /** 删除（驱逐实例 → 清定义/工作区/状态） */
    public Map<String, Object> delete(String id) {
        store.require(id);
        registry.evict(id);
        store.delete(id);
        return Map.of("deleted", id);
    }

    /** 管理列表（含实例存活状态） */
    public List<Map<String, Object>> listForAdmin() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (AgentDefinition def : store.list()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", def.id());
            m.put("name", def.name());
            m.put("description", def.description());
            m.put("status", def.status());
            m.put("version", def.version());
            m.put("updatedAt", def.updatedAt());
            m.put("live", registry.isLive(def.id()));
            result.add(m);
        }
        return result;
    }

    /** chat 页可选智能体清单（全部登录用户；仅上架 active 状态，test 状态走搭建页试运行） */
    public List<Map<String, Object>> listForChat() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (AgentDefinition def : store.list()) {
            if (!AgentDefinition.STATUS_ACTIVE.equals(def.status())) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", def.id());
            m.put("name", def.name());
            m.put("description", def.description());
            m.put("greeting", def.greeting());
            m.put("status", def.status());
            result.add(m);
        }
        return result;
    }

    /** 详情（定义不含密钥；密钥统一经环境变量注入） */
    public Map<String, Object> detail(String id) {
        return detail(store.require(id));
    }

    private Map<String, Object> detail(AgentDefinition def) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", def.id());
        m.put("name", def.name());
        m.put("description", def.description());
        m.put("sysPrompt", def.sysPrompt());
        m.put("greeting", def.greeting());
        m.put("baseUrl", def.model() == null ? "" : def.model().baseUrl());
        m.put("modelName", def.model() == null ? "" : def.model().modelName());
        m.put("maxIters", def.maxIters());
        m.put("skills", def.skills());
        m.put("tools", def.tools());
        m.put("httpTools", def.httpTools());
        m.put("mcps", def.mcps());
        m.put("subagents", def.subagents());
        m.put("projectName", def.projectName());
        m.put("packageName", def.packageName());
        m.put("status", def.status());
        m.put("version", def.version());
        m.put("updatedAt", def.updatedAt());
        return m;
    }

    /** 向导选项面：平台技能清单（SKILL.md frontmatter）+ 内置工具白名单 */
    public Map<String, Object> options() {
        return Map.of(
                "skills", platformSkills(),
                "tools", AgentRegistry.BUILTIN_TOOLS.entrySet().stream()
                        .map(e -> Map.of("name", e.getKey(), "description", e.getValue()))
                        .toList());
    }

    // ---------------- 连通性测试 ----------------

    /** chat 运行权限：active 所有人可用；test 仅管理员（向导试运行） */
    public void requireChatAllowed(String agentId, boolean admin) {
        AgentDefinition def = store.require(agentId);
        if (!AgentDefinition.STATUS_ACTIVE.equals(def.status()) && !admin) {
            throw new ApiException(403, "该智能体未上架，仅管理员可试运行");
        }
    }

    /** 模型连通性：一条最小对话请求，区分 地址错 / key 错 / 模型名错；key 取环境变量 AGENT_API_KEY */
    public Map<String, Object> testModel(String baseUrl, String modelName) {
        if (baseUrl == null || !HTTP_URL.matcher(baseUrl.strip()).matches()) {
            throw new ApiException(400, "base-url 必须以 http:// 或 https:// 开头");
        }
        if (modelName == null || modelName.isBlank()) {
            throw new ApiException(400, "模型名不能为空");
        }
        String apiKey = AgentRegistry.requireEnvApiKey();
        try {
            String body = mapper.writeValueAsString(Map.of(
                    "model", modelName.strip(),
                    "messages", List.of(Map.of("role", "user", "content", "ping")),
                    "max_tokens", 1,
                    "stream", false));
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create(baseUrl.strip().replaceAll("/+$", "") + "/chat/completions"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey.strip())
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString());
            return switch (resp.statusCode()) {
                case 200 -> Map.of("ok", true, "detail", "连接成功，模型可用");
                case 401, 403 -> Map.<String, Object>of("ok", false, "detail", "[连接失败] HTTP "
                        + resp.statusCode() + "：API Key 无效或无权限");
                case 404 -> Map.<String, Object>of("ok", false, "detail", "[连接失败] HTTP 404：地址不存在或模型名错误");
                default -> Map.<String, Object>of("ok", false, "detail", "[服务错误] HTTP "
                        + resp.statusCode() + "：" + clip(resp.body()));
            };
        } catch (java.net.ConnectException | java.net.http.HttpTimeoutException e) {
            return Map.of("ok", false, "detail", "[连接失败] 无法连接 " + baseUrl + "，请检查地址与网络");
        } catch (Exception e) {
            return Map.of("ok", false, "detail", "[连接失败] " + e.getMessage());
        }
    }

    /** MCP 端点握手探测：返回远端工具清单 */
    public Map<String, Object> testMcp(String url) {
        if (url == null || !HTTP_URL.matcher(url.strip()).matches()) {
            throw new ApiException(400, "MCP URL 必须以 http:// 或 https:// 开头");
        }
        try {
            List<String> tools = McpSupport.probe(url.strip(),
                    Duration.ofSeconds(15), Duration.ofSeconds(20));
            return Map.of("ok", true, "tools", tools);
        } catch (Exception e) {
            return Map.of("ok", false, "detail", "[连接失败] " + e.getMessage());
        }
    }

    // ---------------- 导出 ----------------

    /** L1 导出包（zip）：定义导出视图 + workspace（skills/subagents）+ sha256 清单 */
    public Path exportZip(String id) {
        AgentDefinition def = store.require(id);
        if (!AgentRegistry.envApiKeyPresent()) {
            throw new ApiException(400, "未检测到环境变量 AGENT_API_KEY，无法导出"
                    + "（导出包使用 ${AGENT_API_KEY} 占位符，运行时需该环境变量注入密钥）");
        }
        try {
            Path zip = Files.createTempFile("agent-package-" + id, ".zip");
            AgentPackageCodec.write(zip, def, workspaceService.workspaceOf(id), exportedBy, minHarnessVersion);
            return zip;
        } catch (IOException e) {
            throw new ApiException(500, "导出包生成失败：" + e.getMessage());
        }
    }

    /**
     * L2 导出工程（zip）：AgentScope 2.0 原生 Spring Boot 源码工程，
     * 槽位定义转 application.yml 配置驱动，workspace（skills/subagents）原样携带，导出后可深度二次开发。
     * 密钥经 ${AGENT_API_KEY} 环境变量注入，不随工程携带（与 L1 同语义：本地需已设置该环境变量才可导出）。
     */
    public AgentProjectExportService.ExportedProject exportProject(String id) {
        AgentDefinition def = store.require(id);
        if (!AgentRegistry.envApiKeyPresent()) {
            throw new ApiException(400, "未检测到环境变量 AGENT_API_KEY，无法导出"
                    + "（导出工程使用 ${AGENT_API_KEY} 环境变量注入密钥）");
        }
        return projectExporter.export(def, workspaceService.workspaceOf(id));
    }

    // ---------------- 校验 ----------------

    /** 静态校验（[参数错误] 层）：创建与编辑共用 */
    void validate(AgentSaveRequest req) {
        requireLength("名称", req.name(), 1, 30);
        limitLength("描述", req.description(), 200);
        requireLength("系统提示词", req.sysPrompt(), 1, 8000);
        limitLength("开场白", req.greeting(), 500);
        if (!HTTP_URL.matcher(req.baseUrl()).matches()) {
            throw new ApiException(400, "大模型地址必须以 http:// 或 https:// 开头");
        }
        requireLength("模型名", req.modelName(), 1, 100);
        if (req.maxIters() != null && (req.maxIters() < 0 || req.maxIters() > 20)) {
            throw new ApiException(400, "最大推理轮次须在 0-20（0=默认 6）");
        }
        if (!req.skills().isEmpty()) {
            if (workspaceService.platformSkillsDir() == null) {
                throw new ApiException(400, "平台技能目录未配置（app.agent.skills-dir），不可勾选技能");
            }
            for (String s : req.skills()) {
                if (!workspaceService.skillExists(s)) {
                    throw new ApiException(400, "技能不存在：" + s);
                }
            }
        }
        for (String t : req.tools()) {
            if (!AgentRegistry.BUILTIN_TOOLS.containsKey(t)) {
                throw new ApiException(400, "内置工具不在白名单：" + t);
            }
        }
        if (req.httpTools().size() > MAX_HTTP_TOOLS) {
            throw new ApiException(400, "HTTP 工具最多 " + MAX_HTTP_TOOLS + " 个");
        }
        for (AgentDefinition.HttpToolConfig t : req.httpTools()) {
            if (!TOOL_NAME.matcher(t.name()).matches()) {
                throw new ApiException(400, "HTTP 工具名非法（小写字母开头，仅字母/数字/下划线）：" + t.name());
            }
            if (!HTTP_URL.matcher(t.endpoint()).matches()) {
                throw new ApiException(400, "HTTP 工具端点必须以 http:// 或 https:// 开头：" + t.name());
            }
            if (t.pathPrefix().isBlank() || !t.pathPrefix().startsWith("/")) {
                throw new ApiException(400, "HTTP 工具 path 白名单前缀必须以 / 开头：" + t.name());
            }
        }
        if (req.mcps().size() > MAX_MCPS) {
            throw new ApiException(400, "MCP 端点最多 " + MAX_MCPS + " 个");
        }
        for (AgentDefinition.McpConfig m : req.mcps()) {
            if (!HTTP_URL.matcher(m.url()).matches()) {
                throw new ApiException(400, "MCP URL 必须以 http:// 或 https:// 开头");
            }
        }
        if (req.subagents().size() > MAX_SUBAGENTS) {
            throw new ApiException(400, "子智能体最多 " + MAX_SUBAGENTS + " 个（声明式，禁止运行时动态创建）");
        }
        for (AgentDefinition.SubagentSpec s : req.subagents()) {
            if (!Pattern.compile("^[a-z0-9][a-z0-9-]{0,63}$").matcher(s.name()).matches()) {
                throw new ApiException(400, "子智能体名非法（小写字母/数字/连字符）：" + s.name());
            }
            requireLength("子智能体提示词", s.prompt(), 1, 8000);
            limitLength("子智能体描述", s.description(), 200);
        }
        if (!req.projectName().isBlank() && !PROJECT_NAME.matcher(req.projectName()).matches()) {
            throw new ApiException(400, "导出工程名非法（仅英文字母，1-50 位）：" + req.projectName());
        }
        if (!req.packageName().isBlank() && !JAVA_PACKAGE.matcher(req.packageName()).matches()) {
            throw new ApiException(400, "Java 包名非法（小写字母开头，段内仅小写字母/数字/下划线）：" + req.packageName());
        }
    }

    private static String normalizeStatus(String status) {
        return AgentDefinition.STATUS_ACTIVE.equals(status) ? AgentDefinition.STATUS_ACTIVE
                : AgentDefinition.STATUS_TEST;
    }

    private static void requireLength(String field, String value, int min, int max) {
        if (value == null || value.length() < min) {
            throw new ApiException(400, field + "不能为空");
        }
        if (value.length() > max) {
            throw new ApiException(400, field + "长度超过上限 " + max);
        }
    }

    private static void limitLength(String field, String value, int max) {
        if (value != null && value.length() > max) {
            throw new ApiException(400, field + "长度超过上限 " + max);
        }
    }

    /** 平台技能清单：扫描 SKILL.md frontmatter 的 name/description（不加载技能本体） */
    private List<Map<String, String>> platformSkills() {
        List<Map<String, String>> result = new ArrayList<>();
        Path dir = workspaceService.platformSkillsDir();
        if (dir == null || !Files.isDirectory(dir)) {
            return result;
        }
        try (var stream = Files.list(dir)) {
            for (Path skillDir : stream.filter(Files::isDirectory).sorted().toList()) {
                Path skillMd = skillDir.resolve("SKILL.md");
                if (!Files.isRegularFile(skillMd)) {
                    continue;
                }
                String text = Files.readString(skillMd, StandardCharsets.UTF_8);
                Map<String, String> fm = frontmatter(text);
                if (fm.isEmpty()) {
                    continue;
                }
                Map<String, String> m = new LinkedHashMap<>();
                // 技能 ID 统一口径 = 目录名（skillExists/copySkills 按目录拼路径，frontmatter name 仅供 harness 展示）
                m.put("name", skillDir.getFileName().toString());
                m.put("description", fm.getOrDefault("description", ""));
                result.add(m);
            }
        } catch (IOException e) {
            throw new ApiException(500, "平台技能目录扫描失败：" + e.getMessage());
        }
        return result;
    }

    /** 简易 frontmatter 解析：首个 --- 块内的 key: value 行（够用且零依赖） */
    private static Map<String, String> frontmatter(String text) {
        Map<String, String> result = new LinkedHashMap<>();
        String[] lines = text.split("\n", -1);
        int i = 0;
        while (i < lines.length && lines[i].isBlank()) {
            i++;
        }
        if (i >= lines.length || !lines[i].strip().equals("---")) {
            return result;
        }
        for (i++; i < lines.length && !lines[i].strip().equals("---"); i++) {
            int c = lines[i].indexOf(':');
            if (c > 0) {
                result.put(lines[i].substring(0, c).strip(),
                        unquote(lines[i].substring(c + 1).strip()));
            }
        }
        return result;
    }

    /** 去掉 YAML 值首尾成对的引号（name: "xxx" → xxx），避免引号进入路径/数据 */
    private static String unquote(String s) {
        if (s.length() >= 2
                && ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'")))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static String clip(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 300 ? s : s.substring(0, 300) + "...";
    }

    private static String now() {
        return java.time.OffsetDateTime.now().toString();
    }

    private String randomHex(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append("0123456789abcdef".charAt(random.nextInt(16)));
        }
        return sb.toString();
    }
}
