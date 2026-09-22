package com.conca.ontology.dedup.model;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.conca.ontology.shared.web.ApiException;

/**
 * 去重规则单文件存储（与 disjoint-rules.json 同模式）：
 *   全系统仅一套规则，持久化为一个 JSON 文件，无索引文件；active 恒为 true。
 *   文件不存在 = 尚未创建规则（前端显示空白模板，保存即新建）。
 */
@Component
public class DedupRulesFileStore {

    private static final Logger log = LoggerFactory.getLogger(DedupRulesFileStore.class);
    private static final int MAX_SUFFIXES_PER_LABEL = 200;

    private final ObjectMapper mapper;

    @Value("${graph.dedup.rules-file:data/dedup-rules.json}")
    private String rulesFile;

    public DedupRulesFileStore(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** 单文件模式：仅确保父目录存在，不预写空文件（无文件=尚未创建规则） */
    public synchronized void ensureInitialized() {
        Path f = file();
        Path parent = f.getParent();
        try {
            if (parent != null && Files.notExists(parent)) {
                Files.createDirectories(parent);
            }
        } catch (IOException e) {
            throw new IllegalStateException("failed to init dedup rules store dir: " + parent, e);
        }
    }

    public synchronized List<DedupRulesIndexItem> listIndex() {
        DedupRuleset r = readOrNull();
        return r == null ? List.of() : List.of(toIndex(r));
    }

    public synchronized DedupRuleset get(String id) {
        return require(id);
    }

    public synchronized DedupRuleset getActive() {
        return require(null);
    }

    /** 单套规则恒为激活：仅校验存在性与 id 匹配，无状态可切换 */
    public synchronized void setActive(String id) {
        require(id);
    }

    public synchronized DedupRulesIndexItem save(DedupRuleset input) {
        ensureInitialized();
        DedupRuleset existing = readOrNull();
        if (existing != null && input.version() > 0 && existing.version() != input.version()) {
            throw new ApiException(409, "规则已被他人修改（当前 v" + existing.version() + "，提交 v" + input.version() + "），请刷新后重试");
        }
        // 单文件模式：已有规则时沿用其 id；首次创建时用入参 id 或生成 UUID
        String id = existing != null ? existing.id()
                : (input.id() == null || input.id().isBlank() ? UUID.randomUUID().toString() : input.id());
        if (id.chars().anyMatch(c -> !(Character.isLetterOrDigit(c) || c == '-' || c == '_'))) {
            throw new ApiException(400, "规则集 ID 仅允许字母、数字、-、_");
        }
        int version = existing != null ? existing.version() + 1 : 1;
        String name = (input.name() == null || input.name().isBlank()) ? "去重规则" : input.name().trim();

        Map<String, List<String>> suffixTable = new LinkedHashMap<>(input.suffixTable() == null ? Map.of() : input.suffixTable());
        suffixTable.replaceAll((k, v) -> normalizeSuffixes(v));

        Map<String, DedupLabelConfig> labelConfig = new LinkedHashMap<>(input.labelConfig() == null ? Map.of() : input.labelConfig());
        labelConfig.replaceAll((k, v) -> normalizeLabelConfig(v));

        List<String> global = input.globalConflictProps() == null ? List.of()
                : input.globalConflictProps().stream().map(String::trim).filter(s -> !s.isBlank()).distinct().toList();

        DedupRuleset next = new DedupRuleset(id, name, version, System.currentTimeMillis(), suffixTable, labelConfig, global);
        writeOne(next);
        log.info("dedup rules saved: id={} name={} version={} (new={})", id, name, version, existing == null);
        return toIndex(next);
    }

    /** 删除唯一一套规则 → 回到「尚未创建」状态（下次保存即新建） */
    public synchronized void delete(String id) {
        DedupRuleset r = require(id);
        try {
            Files.deleteIfExists(file());
        } catch (IOException e) {
            throw new IllegalStateException("failed to delete dedup rules file: " + file().toAbsolutePath(), e);
        }
        log.info("dedup rule set deleted: {}", r.id());
    }

    // ---------- internals ----------

    private static DedupRulesIndexItem toIndex(DedupRuleset r) {
        return new DedupRulesIndexItem(r.id(), r.name(), r.version(), r.updatedAt(), true);
    }

    /** 后缀清洗：trim + 去引号（粘贴 JSON 文本时带入的 " '）+ 去重 + 限 200 项 + 按长度降序 */
    private List<String> normalizeSuffixes(List<String> list) {
        if (list == null) return List.of();
        List<String> out = list.stream()
                .map(s -> s == null ? "" : s.trim().replace("\"", "").replace("'", "").trim())
                .filter(s -> !s.isEmpty()).distinct().toList();
        if (out.size() > MAX_SUFFIXES_PER_LABEL) {
            throw new ApiException(400, "单个 label 后缀数量不得超过 " + MAX_SUFFIXES_PER_LABEL);
        }
        return out.stream().sorted(Comparator.comparingInt(String::length).reversed()).toList();
    }

    private DedupLabelConfig normalizeLabelConfig(DedupLabelConfig v) {
        if (v == null) return DedupLabelConfig.empty("name");
        String nameProp = (v.nameProp() == null || v.nameProp().isBlank()) ? "name" : v.nameProp().trim();
        List<String> auxProps = v.auxProps() == null ? List.of() : v.auxProps().stream().map(String::trim).filter(s -> !s.isBlank()).distinct().toList();
        List<DedupExactMode> modes = v.exactModes() == null ? List.of() : v.exactModes();
        List<String> dims = v.dimProps() == null ? List.of() : v.dimProps().stream().map(String::trim).filter(s -> !s.isBlank()).distinct().toList();
        List<String> conf = v.conflictProps() == null ? List.of() : v.conflictProps().stream().map(String::trim).filter(s -> !s.isBlank()).distinct().toList();
        return new DedupLabelConfig(nameProp, auxProps, modes, dims, conf);
    }

    private Path file() {
        return Path.of(rulesFile).toAbsolutePath();
    }

    private DedupRuleset readOrNull() {
        Path f = file();
        if (Files.notExists(f)) return null;
        try {
            return mapper.readValue(f.toFile(), DedupRuleset.class);
        } catch (IOException e) {
            log.warn("dedup rules file corrupt ({}), treating as not created: {}", f.toAbsolutePath(), e.getMessage());
            return null;
        }
    }

    private DedupRuleset require(String id) {
        DedupRuleset r = readOrNull();
        if (r == null) {
            throw new ApiException(404, "尚未创建去重规则集，请先在「去重规则配置」页面创建并保存一套规则。");
        }
        if (id != null && !id.isBlank() && !r.id().equals(id)) {
            throw new ApiException(404, "单文件模式仅有一套规则（id=" + r.id() + "），不存在: " + id);
        }
        return r;
    }

    private void writeOne(DedupRuleset r) {
        Path f = file();
        try {
            Path parent = f.getParent();
            if (parent != null) Files.createDirectories(parent);
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), r);
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new IllegalStateException("failed to write dedup rules: " + f.toAbsolutePath(), e);
        }
    }
}
