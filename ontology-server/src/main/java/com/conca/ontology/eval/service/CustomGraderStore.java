package com.conca.ontology.eval.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.conca.ontology.eval.entity.CustomGraderEntity;
import com.conca.ontology.eval.mapper.CustomGraderMapper;
import com.conca.ontology.eval.model.CustomGraderDefinition;
import com.conca.ontology.shared.web.ApiException;

/**
 * 自定义评测器持久层（MySQL custom_grader，P6 起替代 data/eval/custom-graders.json 单文件）：
 * 每评测器一行（name 主键），行级 CRUD（find 单行查询 / all 按名称字典序，与注册表 TreeMap 同序）。
 * 校验逻辑（validate）与 409/404 文案不变；CRUD 保留 synchronized（find+insert 竞态防护，对齐原实现并发语义）。
 */
@Component
public class CustomGraderStore {

    private static final Pattern NAME_RE = Pattern.compile("^[a-z0-9_]{3,32}$");
    private static final Pattern PLACEHOLDER_RE = Pattern.compile("\\{\\{(\\w+)}}");
    private static final List<String> PLACEHOLDERS = List.of("query", "answer", "reference", "context", "rubric");
    private static final int PROMPT_LIMIT = 4000;

    private final CustomGraderMapper graderMapper;

    public CustomGraderStore(CustomGraderMapper graderMapper) {
        this.graderMapper = graderMapper;
    }

    public synchronized List<CustomGraderDefinition> all() {
        List<CustomGraderDefinition> list = new ArrayList<>();
        for (CustomGraderEntity e : graderMapper.selectList(Wrappers.<CustomGraderEntity>lambdaQuery()
                .orderByAsc(CustomGraderEntity::getName))) {
            list.add(e.toModel());
        }
        return list;
    }

    public synchronized CustomGraderDefinition find(String name) {
        CustomGraderEntity e = graderMapper.selectById(name);
        return e == null ? null : e.toModel();
    }

    public synchronized CustomGraderDefinition create(CustomGraderDefinition def) {
        if (find(def.name()) != null) {
            throw new ApiException(409, "自定义评测器已存在: " + def.name());
        }
        graderMapper.insert(CustomGraderEntity.of(def));
        return def;
    }

    public synchronized CustomGraderDefinition update(String name, CustomGraderDefinition next) {
        if (find(name) == null) {
            throw new ApiException(404, "自定义评测器不存在: " + name);
        }
        graderMapper.updateById(CustomGraderEntity.of(next));
        return next;
    }

    public synchronized void delete(String name) {
        if (graderMapper.deleteById(name) == 0) {
            throw new ApiException(404, "自定义评测器不存在: " + name);
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
