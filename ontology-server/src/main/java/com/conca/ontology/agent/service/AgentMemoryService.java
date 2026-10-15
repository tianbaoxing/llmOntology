package com.conca.ontology.agent.service;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.conca.ontology.shared.web.ApiException;
import com.conca.ontology.user.service.UserService;

/**
 * Agent 记忆查看（只读）：扫描 workspace/{userId}/ 下的记忆文件，
 * 供 users 页管理员查看各用户的记忆沉淀。
 * - MEMORY.md：策展长期记忆（LLM 整合后的汇总），按「## 日期」分段；
 * - memory/yyyy-MM-dd.md：每日记忆（轮末自动沉淀），按「## Memory Flush — 时间戳」分段；
 * - 目录/文件不存在一律返回空列表（新用户无 workspace 是正常态，不抛错）。
 * 安全：userId 先经 UserService 校验存在；文件名白名单（仅 MEMORY.md 或日期格式），防路径穿越。
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

    private final UserService users;

    @Value("${app.agent.workspace-dir:data/agent-workspace}")
    private String workspaceDir;

    public AgentMemoryService(UserService users) {
        this.users = users;
    }

    /** 记忆文件列表：curated（MEMORY.md 元信息）+ daily（每日文件，日期倒序） */
    public Map<String, Object> list(String userId) {
        requireUser(userId);
        Path userDir = Path.of(workspaceDir, userId);
        Path memoryDir = userDir.resolve("memory");

        Map<String, Object> curated = null;
        Path curatedFile = userDir.resolve(CURATED_NAME);
        if (Files.isRegularFile(curatedFile)) {
            String text = read(curatedFile);
            curated = fileMeta(CURATED_NAME, text, countSections(text, DATE_HEADER), curatedFile);
        }

        List<Map<String, Object>> daily = new ArrayList<>();
        if (Files.isDirectory(memoryDir)) {
            try (Stream<Path> s = Files.list(memoryDir)) {
                s.filter(p -> {
                            String n = p.getFileName().toString();
                            return DAILY_NAME.matcher(n).matches() && Files.isRegularFile(p);
                        })
                        .forEach(p -> {
                            String text = read(p);
                            daily.add(fileMeta(p.getFileName().toString(), text,
                                    countSections(text, FLUSH_HEADER), p));
                        });
            } catch (IOException e) {
                throw new ApiException(500, "读取记忆目录失败: " + e.getMessage());
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
        Path file = CURATED_NAME.equals(name)
                ? Path.of(workspaceDir, userId, name)
                : Path.of(workspaceDir, userId, "memory", name);
        if (!Files.isRegularFile(file)) {
            throw new ApiException(404, "记忆文件不存在: " + name);
        }
        String text = read(file);

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

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ApiException(500, "读取记忆文件失败: " + file.getFileName());
        }
    }

    private static Map<String, Object> fileMeta(String name, String text, int entries, Path file) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("entries", entries);
        m.put("size", text.getBytes(StandardCharsets.UTF_8).length);
        try {
            m.put("lastModified", Files.getLastModifiedTime(file).toString());
        } catch (IOException e) {
            m.put("lastModified", "");
        }
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
