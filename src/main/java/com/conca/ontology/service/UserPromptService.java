package com.conca.ontology.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.conca.ontology.exception.ApiException;
import com.conca.ontology.model.UserPromptEntry;
import com.conca.ontology.model.UserPromptFile;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 用户提示词管理：data/prompts/{userId}.json 唯一事实源（version + tmp 原子写 + synchronized），
 * 按登录用户隔离（userId 由 Controller 从登录态传入）。chat 页「#」快捷菜单与管理弹窗的数据来源。
 */
@Service
public class UserPromptService {

    private static final Logger log = LoggerFactory.getLogger(UserPromptService.class);
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** userId 与注册用户名同源字符集，先挡路径穿越 */
    private static final Pattern USER_ID_RE = Pattern.compile("^[A-Za-z0-9_\\u4e00-\\u9fa5]{2,32}$");

    private static final int MAX_PROMPTS = 200;

    private final ObjectMapper mapper;

    @Value("${app.prompts-dir:data/prompts}")
    private String promptsDir;

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
}
