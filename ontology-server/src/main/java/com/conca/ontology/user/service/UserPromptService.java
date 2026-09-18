package com.conca.ontology.user.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.conca.ontology.shared.web.ApiException;
import com.conca.ontology.user.model.UserPromptEntry;
import com.conca.ontology.user.model.UserPromptFile;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 用户提示词管理：data/prompts/{userId}.json 唯一事实源（version + tmp 原子写 + synchronized），
 * 按登录用户隔离（userId 由 Controller 从登录态传入）。chat 页「#」快捷菜单与管理弹窗的数据来源。
 *
 * 提示词沉淀为 skill：写 {@code skills/{name}/SKILL.md + prompt-meta.json}，
 * skills 目录经 HarnessSkillMiddleware 每请求动态扫描，转换后新会话即可被 LLM 感知。
 */
@Service
public class UserPromptService {

    private static final Logger log = LoggerFactory.getLogger(UserPromptService.class);
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** userId 与注册用户名同源字符集，先挡路径穿越 */
    private static final Pattern USER_ID_RE = Pattern.compile("^[A-Za-z0-9_\\u4e00-\\u9fa5]{2,32}$");

    /** skill 名规范同 harness SkillManageTool：小写字母/数字/连字符，≤64 字符，连字符不首尾 */
    private static final Pattern SKILL_NAME_RE = Pattern.compile("^[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?$");

    private static final int MAX_PROMPTS = 200;

    /** 用户提示词 skill 的归属元数据（防删他人/系统 skill 的依据） */
    record PromptSkillMeta(String owner, String sourcePromptId, String title, String convertedAt) {
    }

    private final ObjectMapper mapper;

    @Value("${app.prompts-dir:data/prompts}")
    private String promptsDir;

    @Value("${app.agent.skills-dir:skills}")
    private String skillsDir;

    public UserPromptService(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** 列表（updatedAt 倒序）；无文件视为空列表 */
    public synchronized List<UserPromptEntry> list(String userId) {
        List<UserPromptEntry> items = new ArrayList<>(load(userId).items());
        items.sort(Comparator.comparing(UserPromptEntry::updatedAt, Comparator.reverseOrder()));
        return items;
    }

    public synchronized UserPromptEntry create(String userId, String title, String content) {
        UserPromptFile pf = load(userId);
        if (pf.items().size() >= MAX_PROMPTS) {
            throw new ApiException(400, "提示词已达上限 " + MAX_PROMPTS + " 条，请先删除部分条目");
        }
        String now = now();
        UserPromptEntry entry = new UserPromptEntry(UUID.randomUUID().toString(), title, content, now, now);
        List<UserPromptEntry> items = new ArrayList<>(pf.items());
        items.add(entry);
        save(userId, new UserPromptFile(pf.version() + 1, items));
        log.info("user prompt created: {} ({})", userId, entry.id());
        return entry;
    }

    public synchronized UserPromptEntry update(String userId, String id, String title, String content) {
        UserPromptFile pf = load(userId);
        List<UserPromptEntry> items = new ArrayList<>();
        UserPromptEntry replaced = null;
        for (UserPromptEntry e : pf.items()) {
            if (e.id().equals(id)) {
                replaced = new UserPromptEntry(e.id(), title, content, e.createdAt(), now());
                items.add(replaced);
            } else {
                items.add(e);
            }
        }
        if (replaced == null) {
            throw new ApiException(404, "提示词不存在");
        }
        save(userId, new UserPromptFile(pf.version() + 1, items));
        log.info("user prompt updated: {} ({})", userId, id);
        return replaced;
    }

    public synchronized void delete(String userId, String id) {
        UserPromptFile pf = load(userId);
        List<UserPromptEntry> items = new ArrayList<>(pf.items());
        if (!items.removeIf(e -> e.id().equals(id))) {
            throw new ApiException(404, "提示词不存在");
        }
        save(userId, new UserPromptFile(pf.version() + 1, items));
        log.info("user prompt deleted: {} ({})", userId, id);
    }

    // ---------------- 内部 ----------------

    private static String now() {
        return LocalDateTime.now().format(TIME_FMT);
    }

    private UserPromptFile load(String userId) {
        Path p = fileOf(userId);
        if (!Files.isRegularFile(p)) {
            return UserPromptFile.empty();
        }
        try {
            UserPromptFile pf = mapper.readValue(Files.readString(p, StandardCharsets.UTF_8), UserPromptFile.class);
            if (pf.items() == null) {
                return new UserPromptFile(pf.version(), new ArrayList<>());
            }
            return pf;
        } catch (IOException e) {
            throw new ApiException(500, "提示词文件读取失败: " + e.getMessage());
        }
    }

    private void save(String userId, UserPromptFile pf) {
        try {
            Path p = fileOf(userId);
            Files.createDirectories(p.getParent());
            Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
            Files.writeString(tmp, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(pf),
                    StandardCharsets.UTF_8);
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new ApiException(500, "提示词文件写入失败: " + e.getMessage());
        }
    }

    private Path fileOf(String userId) {
        if (userId == null || !USER_ID_RE.matcher(userId).matches()) {
            throw new ApiException(400, "用户标识不合法");
        }
        return Path.of(promptsDir).toAbsolutePath().normalize().resolve(userId + ".json");
    }

    // ---------------- 提示词 → skill 沉淀 ----------------

    /**
     * 将提示词转为 skill：写 skills/{name}/SKILL.md + prompt-meta.json。
     * name 缺省自动生成；description 缺省按模板生成。返回最终 skill 名。
     */
    public synchronized Map<String, Object> toSkill(String userId, String promptId, String name, String description) {
        UserPromptEntry src = null;
        for (UserPromptEntry e : load(userId).items()) {
            if (e.id().equals(promptId)) {
                src = e;
                break;
            }
        }
        if (src == null) {
            throw new ApiException(404, "提示词不存在");
        }
        String finalName = (name == null || name.isBlank()) ? randomSkillName() : name.trim();
        if (!SKILL_NAME_RE.matcher(finalName).matches()) {
            throw new ApiException(400, "skill 名只能包含小写字母、数字、连字符，且不能以连字符开头或结尾");
        }
        Path dir = skillDirOf(finalName);
        if (Files.exists(dir)) {
            throw new ApiException(400, "skill 名已存在：" + finalName);
        }
        String desc = (description == null || description.isBlank())
                ? "用户沉淀的提示词技能「" + src.title() + "」。当用户的问题与「" + src.title() + "」相关时，遵循本技能中的指引作答。"
                : description.trim();
        if (desc.length() > 1024) {
            throw new ApiException(400, "description 最长 1024 字");
        }
        try {
            Files.createDirectories(dir);
            String skillMd = "---\n"
                    + "name: " + yamlQuote(finalName) + "\n"
                    + "description: " + yamlQuote(desc) + "\n"
                    + "---\n\n"
                    + "# " + src.title() + "\n\n"
                    + src.content() + "\n";
            writeAtomic(dir.resolve("SKILL.md"), skillMd);
            PromptSkillMeta meta = new PromptSkillMeta(userId, src.id(), src.title(), now());
            writeAtomic(dir.resolve("prompt-meta.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(meta));
        } catch (IOException e) {
            throw new ApiException(500, "skill 文件写入失败: " + e.getMessage());
        }
        log.info("user prompt converted to skill: {} -> {} ({})", userId, finalName, src.id());
        return Map.of("name", finalName);
    }

    /** 删除用户提示词 skill：仅允许删除 owner 为本人的转换目录；系统 skill（无 meta）拒绝 */
    public synchronized void deleteSkill(String userId, String name) {
        if (name == null || !SKILL_NAME_RE.matcher(name).matches()) {
            throw new ApiException(400, "skill 名不合法");
        }
        Path dir = skillDirOf(name);
        if (!Files.isDirectory(dir)) {
            throw new ApiException(404, "skill 不存在");
        }
        Path metaPath = dir.resolve("prompt-meta.json");
        if (!Files.isRegularFile(metaPath)) {
            throw new ApiException(403, "该 skill 不是用户提示词转换生成，不允许在此删除");
        }
        PromptSkillMeta meta;
        try {
            meta = mapper.readValue(Files.readString(metaPath, StandardCharsets.UTF_8), PromptSkillMeta.class);
        } catch (IOException e) {
            throw new ApiException(500, "skill 元数据读取失败: " + e.getMessage());
        }
        if (meta == null || !userId.equals(meta.owner())) {
            throw new ApiException(403, "该 skill 由其他用户转换生成，不允许删除");
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException | UncheckedIOException e) {
            throw new ApiException(500, "skill 目录删除失败: " + e.getMessage());
        }
        log.info("user prompt skill deleted: {} ({})", userId, name);
    }

    /** 提示词 id → 已转 skill 名列表（仅本人转换的），供管理弹窗展示「已转」标记 */
    public Map<String, List<String>> skillIndex(String userId) {
        Map<String, List<String>> idx = new HashMap<>();
        Path root = Path.of(skillsDir).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            return idx;
        }
        try (Stream<Path> dirs = Files.list(root)) {
            dirs.filter(Files::isDirectory).forEach(dir -> {
                PromptSkillMeta meta = readMeta(dir);
                if (meta != null && userId.equals(meta.owner()) && meta.sourcePromptId() != null) {
                    idx.computeIfAbsent(meta.sourcePromptId(), k -> new ArrayList<>()).add(dir.getFileName().toString());
                }
            });
        } catch (IOException e) {
            log.warn("skills 目录扫描失败: {}", e.getMessage());
        }
        return idx;
    }

    // ---------------- skill 内部 ----------------

    private Path skillDirOf(String name) {
        return Path.of(skillsDir).toAbsolutePath().normalize().resolve(name);
    }

    /** 读用户提示词 skill 的归属元数据；目录非转换生成（无 meta / 解析失败）返回 null */
    private PromptSkillMeta readMeta(Path dir) {
        Path metaPath = dir.resolve("prompt-meta.json");
        if (!Files.isRegularFile(metaPath)) {
            return null;
        }
        try {
            return mapper.readValue(Files.readString(metaPath, StandardCharsets.UTF_8), PromptSkillMeta.class);
        } catch (IOException e) {
            return null;
        }
    }

    /** 自动生成未占用的 skill 名：user-<6 位小写短码> */
    private String randomSkillName() {
        for (int i = 0; i < 8; i++) {
            String code = UUID.randomUUID().toString().replace("-", "").substring(0, 6);
            if (!Files.exists(skillDirOf("user-" + code))) {
                return "user-" + code;
            }
        }
        throw new ApiException(500, "skill 名自动生成失败，请手动指定");
    }

    /** YAML 双引号字符串转义（description 可含任意中文/引号） */
    private static String yamlQuote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static void writeAtomic(Path target, String content) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
