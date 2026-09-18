package com.conca.ontology.eval.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.conca.ontology.eval.model.CustomGraderDefinition;
import com.conca.ontology.shared.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;

@Component
public class CustomGraderStore {

    private static final Logger log = LoggerFactory.getLogger(CustomGraderStore.class);

    private static final Pattern NAME_RE = Pattern.compile("^[a-z0-9_]{3,32}$");
    private static final Pattern PLACEHOLDER_RE = Pattern.compile("\\{\\{(\\w+)}}");
    private static final List<String> PLACEHOLDERS = List.of("query", "answer", "reference", "context", "rubric");
    private static final int PROMPT_LIMIT = 4000;

    private final ObjectMapper mapper;
    private final List<CustomGraderDefinition> defs = new ArrayList<>();

    @Value("${app.eval.custom-graders-file:data/eval/custom-graders.json}")
    private String file;

    public CustomGraderStore(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @PostConstruct
    void load() {
        Path p = Path.of(file);
        if (!Files.isRegularFile(p)) {
            return;
        }
        try {
            CustomGraderDefinition[] arr = mapper.readValue(p.toFile(), CustomGraderDefinition[].class);
            for (CustomGraderDefinition d : arr) {
                defs.add(d);
            }
            log.info("loaded {} custom graders from {}", defs.size(), file);
        } catch (IOException e) {
            throw new IllegalStateException("自定义评测器文件解析失败（" + file + "）: " + e.getMessage(), e);
        }
    }

    public synchronized List<CustomGraderDefinition> all() {
        return new ArrayList<>(defs);
    }

    public synchronized CustomGraderDefinition find(String name) {
        return defs.stream().filter(d -> d.name().equals(name)).findFirst().orElse(null);
    }

    public synchronized CustomGraderDefinition create(CustomGraderDefinition def) {
        if (find(def.name()) != null) {
            throw new ApiException(409, "自定义评测器已存在: " + def.name());
        }
        defs.add(def);
        persist();
        return def;
    }

    public synchronized CustomGraderDefinition update(String name, CustomGraderDefinition next) {
        for (int i = 0; i < defs.size(); i++) {
            if (defs.get(i).name().equals(name)) {
                defs.set(i, next);
                persist();
                return next;
            }
        }
        throw new ApiException(404, "自定义评测器不存在: " + name);
    }

    public synchronized void delete(String name) {
        if (!defs.removeIf(d -> d.name().equals(name))) {
            throw new ApiException(404, "自定义评测器不存在: " + name);
        }
        persist();
    }

    private void persist() {
        Path p = Path.of(file);
        try {
            Files.createDirectories(p.toAbsolutePath().getParent());
            Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), defs);
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new ApiException(500, "写入自定义评测器文件失败: " + e.getMessage());
        }
    }

    public static CustomGraderDefinition validate(String name, String description, String mode,
            String prompt, String createdAt) {
        String n = name == null ? "" : name.trim();
        if (!NAME_RE.matcher(n).matches()) {
            throw new ApiException(400, "名称须为 3-32 位小写字母/数字/下划线（如 my_concise_check）");
        }
        String m = mode == null || mode.isBlank() ? CustomGraderDefinition.MODE_CRITERIA : mode.trim();
        if (!m.equals(CustomGraderDefinition.MODE_CRITERIA) && !m.equals(CustomGraderDefinition.MODE_TEMPLATE)) {
            throw new ApiException(400, "mode 仅支持 criteria（评分标准）| template（完整模板）");
        }
        String p = prompt == null ? "" : prompt.trim();
        if (p.isBlank()) {
            throw new ApiException(400, "提示词不能为空");
        }
        if (p.length() > PROMPT_LIMIT) {
            throw new ApiException(400, "提示词过长（" + p.length() + " 字符，上限 " + PROMPT_LIMIT + "）");
        }
        if (m.equals(CustomGraderDefinition.MODE_TEMPLATE)) {
            if (!p.contains("{{answer}}")) {
                throw new ApiException(400, "template 模式提示词须包含 {{answer}}（否则评分与被评估回答无关）");
            }
            Matcher mat = PLACEHOLDER_RE.matcher(p);
            while (mat.find()) {
                if (!PLACEHOLDERS.contains(mat.group(1))) {
                    throw new ApiException(400, "未知占位符 {{" + mat.group(1)
                            + "}}（可用：" + PLACEHOLDERS + "）");
                }
            }
        }
        return new CustomGraderDefinition(n,
                description == null ? "" : description.trim(),
                m, p, createdAt, java.time.LocalDateTime.now().toString());
    }
}
