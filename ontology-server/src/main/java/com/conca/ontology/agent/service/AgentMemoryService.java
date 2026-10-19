package com.conca.ontology.agent.service;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import com.conca.ontology.shared.web.ApiException;
import com.conca.ontology.user.service.UserService;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.workspace.WorkspaceManager;

/**
 * Agent 记忆查看（只读）：经主智能体 WorkspaceManager 读取 workspace/{userId}/ 下的记忆文件，
 * 供 users 页管理员查看各用户的记忆沉淀。本机模式读本地磁盘，共享存储模式（RemoteFilesystemSpec）
 * 走框架两层读（KV 优先、本地种子兜底），双模式同一套代码。
 * - MEMORY.md：策展长期记忆（LLM 整合后的汇总），按「## 日期」分段；
 * - memory/yyyy-MM-dd.md：每日记忆（轮末自动沉淀），按「## Memory Flush — 时间戳」分段；
 * - 文件不存在或智能体未装配（api-key 未配置，记忆本就无从产生）一律返回空，不抛错；
 * - 单文件读取失败按不存在处理（尽力语义，避免个别 KV 抖动打死整个列表）。
 * 安全：userId 先经 UserService 校验存在；文件名白名单（仅 MEMORY.md 或日期格式），防路径穿越。
 * 注：共享存储模式下经 KV 读取拿不到文件修改时间，lastModified 一律返回空字符串。
 *
 * @author tianbaoxing
 * @date 2026-08-25
 */
@Service
public class AgentMemoryService {

    /** 策展长期记忆文件名（workspace/{userId}/ 根下） */
    static final String CURATED_NAME = "MEMORY.md";
    /** 文件名白名单：MEMORY.md 或 yyyy-MM-dd.md */
    private static final Pattern FILE_NAME = Pattern.compile("^(MEMORY\\.md|\\d{4}-\\d{2}-\\d{2}\\.md)$");
    /** 每日记忆文件名日期格式 */
    private static final Pattern DAILY_NAME = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}\\.md$");
    /** 每日记忆分段标记：## Memory Flush — ISO时间戳 */
    private static final Pattern FLUSH_HEADER = Pattern.compile("^##\\s+Memory Flush\\s*[—-]\\s*(.+)$");
    /** 策展记忆分段标记：## 日期 */
    private static final Pattern DATE_HEADER = Pattern.compile("^##\\s+(\\d{4}-\\d{2}-\\d{2}).*$");
    /** 记忆为用户层数据，与具体会话无关：读操作用固定占位 sessionId（USER 级隔离下不影响命名空间路由） */
    private static final String VIEW_SESSION = "memory-view";

    private final UserService users;
    private final ObjectProvider<HarnessAgent> agentProvider;

    public AgentMemoryService(UserService users, ObjectProvider<HarnessAgent> agentProvider) {
        this.users = users;
        this.agentProvider = agentProvider;
    }

    /** 记忆文件列表：curated（MEMORY.md 元信息）+ daily（每日文件，日期倒序） */
    public Map<String, Object> list(String userId) {
        requireUser(userId);
        WorkspaceManager wm = workspaceManagerOf(userId);

        Map<String, Object> curated = null;
        List<Map<String, Object>> daily = new ArrayList<>();
        if (wm != null) {
            RuntimeContext rc = viewContext(userId);
            String curatedText = readCurated(wm, rc);
            if (curatedText != null) {
                curated = fileMeta(CURATED_NAME, curatedText, countSections(curatedText, DATE_HEADER));
            }
            for (String relativePath : listDailyPaths(wm, rc)) {
                String name = fileNameOf(relativePath);
                if (!DAILY_NAME.matcher(name).matches()) {
                    continue;
                }
                String text = readMemoryFile(wm, rc, relativePath);
                if (text == null) {
                    continue;
                }
                daily.add(fileMeta(name, text, countSections(text, FLUSH_HEADER)));
            }
        }
        // 日期倒序（文件名即日期，字典序比较即可）
        daily.sort(Comparator.comparing(m -> String.valueOf(m.get("name")), Comparator.reverseOrder()));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", userId);
        result.put("curated", curated);
        result.put("daily", daily);
        return result;
    }

    /**
     * 单个记忆文件详情（分段结构化）：
     * curated（MEMORY.md）→ sections[{date, content}]；
     * 每日文件 → sections[{time, content}]。
     */
    public Map<String, Object> detail(String userId, String name) {
        requireUser(userId);
        if (name == null || !FILE_NAME.matcher(name).matches()) {
            throw new ApiException(400, "非法文件名");
        }
        WorkspaceManager wm = workspaceManagerOf(userId);
        String text = null;
        if (wm != null) {
            RuntimeContext rc = viewContext(userId);
            text = CURATED_NAME.equals(name)
                    ? readCurated(wm, rc)
                    : readMemoryFile(wm, rc, "memory/" + name);
        }
        if (text == null) {
            throw new ApiException(404, "记忆文件不存在: " + name);
        }

        boolean curated = CURATED_NAME.equals(name);
        List<Map<String, Object>> sections = parseSections(text,
                curated ? DATE_HEADER : FLUSH_HEADER,
                curated ? "date" : "time");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", userId);
        result.put("name", name);
        result.put("type", curated ? "curated" : "daily");
        result.put("size", text.getBytes(StandardCharsets.UTF_8).length);
        result.put("sections", sections);
        return result;
    }

    // ---------------- 内部 ----------------

    /** 用户不存在 → 404（同时天然挡住路径穿越片段） */
    private void requireUser(String userId) {
        if (userId == null || !users.exists(userId)) {
            throw new ApiException(404, "用户不存在: " + userId);
        }
    }

    /** 主智能体 WorkspaceManager；未装配（api-key 未配置）返回 null */
    private WorkspaceManager workspaceManagerOf(String userId) {
        HarnessAgent agent = agentProvider.getIfAvailable();
        return agent == null ? null : agent.workspaceFor(userId, VIEW_SESSION);
    }

    private static RuntimeContext viewContext(String userId) {
        return RuntimeContext.builder().userId(userId).sessionId(VIEW_SESSION).build();
    }

    /** 读策展记忆 MEMORY.md；不存在/读取失败返回 null */
    private static String readCurated(WorkspaceManager wm, RuntimeContext rc) {
        try {
            return wm.readMemoryMd(rc);
        } catch (Exception e) {
            return null;
        }
    }

    /** 读单个记忆文件；不存在/读取失败返回 null */
    private static String readMemoryFile(WorkspaceManager wm, RuntimeContext rc, String relativePath) {
        try {
            return wm.readManagedWorkspaceFileUtf8(rc, relativePath);
        } catch (Exception e) {
            return null;
        }
    }

    /** 列每日记忆文件相对路径；目录不存在/读取失败返回空列表 */
    private static List<String> listDailyPaths(WorkspaceManager wm, RuntimeContext rc) {
        try {
            List<String> paths = wm.listMemoryFilePaths(rc);
            return paths == null ? List.of() : paths;
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 相对路径取尾段文件名（正反斜杠兼容） */
    private static String fileNameOf(String relativePath) {
        if (relativePath == null) {
            return "";
        }
        String p = relativePath.replace('\\', '/');
        int idx = p.lastIndexOf('/');
        return idx >= 0 ? p.substring(idx + 1) : p;
    }

    private static Map<String, Object> fileMeta(String name, String text, int entries) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("entries", entries);
        m.put("size", text.getBytes(StandardCharsets.UTF_8).length);
        m.put("lastModified", "");
        return m;
    }

    /** 统计二/三级标题分节数量（entries 展示用） */
    private static int countSections(String text, Pattern header) {
        int count = 0;
        for (String line : text.split("\n", -1)) {
            if (header.matcher(line.strip()).matches()) {
                count++;
            }
        }
        return count;
    }

    /** 按标题正则分段：每组 {keyName: 标题捕获组, content: 该节正文}；标题前的散行并入首节（key 置空） */
    private static List<Map<String, Object>> parseSections(String text, Pattern header, String keyName) {
        List<Map<String, Object>> sections = new ArrayList<>();
        String currentKey = null;
        StringBuilder current = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            java.util.regex.Matcher m = header.matcher(line.strip());
            if (m.matches()) {
                if (currentKey != null || current.length() > 0) {
                    sections.add(section(keyName, currentKey, current.toString()));
                }
                currentKey = m.group(1).strip();
                current = new StringBuilder();
            } else {
                current.append(line).append('\n');
            }
        }
        if (currentKey != null || current.length() > 0) {
            sections.add(section(keyName, currentKey, current.toString()));
        }
        // 去掉空洞的首节（无标题又无内容）
        sections.removeIf(s -> s.get(keyName) == null && String.valueOf(s.get("content")).isBlank());
        return sections;
    }

    private static Map<String, Object> section(String keyName, String key, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(keyName, key);
        m.put("content", content.strip());
        return m;
    }
}
