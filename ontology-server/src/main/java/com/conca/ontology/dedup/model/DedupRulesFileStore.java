package com.conca.ontology.dedup.model;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.conca.ontology.shared.web.ApiException;
import com.conca.ontology.dedup.model.DedupLabelConfig;
import com.conca.ontology.dedup.model.DedupRuleset;
import com.conca.ontology.dedup.model.DedupRulesIndexItem;

@Component
public class DedupRulesFileStore {

    private static final Logger log = LoggerFactory.getLogger(DedupRulesFileStore.class);
    private static final String INDEX_FILE = "rules-index.json";
    private static final int MAX_SUFFIXES_PER_LABEL = 200;

    private final ObjectMapper mapper;

    @Value("${graph.dedup.rules-dir:data/dedup/rules}")
    private String rulesDir;

    public DedupRulesFileStore(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public synchronized void ensureInitialized() {
        Path dir = rulesDir();
        Path indexFile = dir.resolve(INDEX_FILE);
        try {
            if (Files.notExists(dir)) {
                Files.createDirectories(dir);
                log.info("dedup rules dir created: {}", dir.toAbsolutePath());
            }
            if (Files.notExists(indexFile)) {
                List<DedupRulesIndexItem> idx = List.of();
                writeIndex(idx);
                log.info("dedup rules initialized with empty index (no built-in ruleset)");
            }
        } catch (Exception e) {
            throw new IllegalStateException("failed to init dedup rules store at " + dir.toAbsolutePath(), e);
        }
    }

    public synchronized List<DedupRulesIndexItem> listIndex() {
        ensureInitialized();
        List<DedupRulesIndexItem> idx = readIndex();
        idx.sort(Comparator.comparing((DedupRulesIndexItem i) -> !i.active())
                .thenComparingLong(DedupRulesIndexItem::updatedAt).reversed());
        return idx;
    }

    public synchronized DedupRuleset get(String id) {
        ensureInitialized();
        if (id == null || id.isBlank()) {
            return getActive();
        }
        Path f = rulesDir().resolve(safeFileName(id) + ".json");
        if (Files.notExists(f)) {
            throw new ApiException(404, "规则集不存在: " + id);
        }
        try {
            return mapper.readValue(f.toFile(), DedupRuleset.class);
        } catch (IOException e) {
            throw new IllegalStateException("failed to read dedup rules file: " + f.toAbsolutePath(), e);
        }
    }

    public synchronized DedupRuleset getActive() {
        ensureInitialized();
        List<DedupRulesIndexItem> idx = readIndex();
        String activeId = idx.stream().filter(DedupRulesIndexItem::active)
                .map(DedupRulesIndexItem::id).findFirst().orElse(null);
        if (activeId == null) {
            throw new ApiException(404, "当前没有激活的去重规则集，请先在「去重规则配置」页面创建并保存一套规则。");
        }
        return get(activeId);
    }

    public synchronized void setActive(String id) {
        ensureInitialized();
        List<DedupRulesIndexItem> idx = readIndex();
        boolean found = idx.stream().anyMatch(i -> id.equals(i.id()));
        if (!found) throw new ApiException(404, "规则集不存在: " + id);
        List<DedupRulesIndexItem> next = idx.stream()
                .map(i -> new DedupRulesIndexItem(i.id(), i.name(), i.version(), i.updatedAt(), id.equals(i.id())))
                .toList();
        writeIndex(next);
        log.info("dedup active rule set: {}", id);
    }

    public synchronized DedupRulesIndexItem save(DedupRuleset input) {
        ensureInitialized();
        String id = (input.id() == null || input.id().isBlank()) ? UUID.randomUUID().toString() : input.id();
        boolean isNew = Files.notExists(rulesDir().resolve(safeFileName(id) + ".json"));
        if (id.chars().anyMatch(c -> !(Character.isLetterOrDigit(c) || c == '-' || c == '_'))) {
            throw new ApiException(400, "规则集 ID 仅允许字母、数字、-、_");
        }
        DedupRuleset existing = null;
        if (!isNew) {
            existing = get(id);
            if (input.version() > 0 && existing.version() != input.version()) {
                throw new ApiException(409, "规则已被他人修改（当前 v" + existing.version() + "，提交 v" + input.version() + "），请刷新后重试");
            }
        }
        int version = existing != null ? existing.version() + 1 : 1;
        String name = (input.name() == null || input.name().isBlank()) ? ("规则集 " + id.substring(0, 6)) : input.name().trim();

        Map<String, List<String>> suffixTable = new LinkedHashMap<>(input.suffixTable() == null ? Map.of() : input.suffixTable());
        suffixTable.replaceAll((k, v) -> normalizeSuffixes(v));

        Map<String, DedupLabelConfig> labelConfig = new LinkedHashMap<>(input.labelConfig() == null ? Map.of() : input.labelConfig());
        labelConfig.replaceAll((k, v) -> normalizeLabelConfig(v));

        List<String> global = new ArrayList<>(input.globalConflictProps() == null ? List.of() : input.globalConflictProps());
        global = global.stream().map(String::trim).filter(s -> !s.isBlank()).distinct().toList();

        DedupRuleset next = new DedupRuleset(id, name, version, System.currentTimeMillis(),
                suffixTable, labelConfig, global);
        writeOne(next);

        List<DedupRulesIndexItem> idx = readIndex();
        List<DedupRulesIndexItem> nextIdx = new ArrayList<>(idx);
        int pos = -1;
        for (int i = 0; i < nextIdx.size(); i++) {
            if (id.equals(nextIdx.get(i).id())) { pos = i; break; }
        }
        boolean hadActive = idx.stream().anyMatch(DedupRulesIndexItem::active);
        DedupRulesIndexItem item = toIndex(next, !hadActive);
        if (pos >= 0) nextIdx.set(pos, item);
        else nextIdx.add(item);
        writeIndex(nextIdx);

        log.info("dedup rules saved: id={} name={} version={} (new={})", id, name, version, isNew);
        return toIndex(next, item.active());
    }

    public synchronized void delete(String id) {
        ensureInitialized();
        Path f = rulesDir().resolve(safeFileName(id) + ".json");
        if (Files.notExists(f)) throw new ApiException(404, "规则集不存在: " + id);
        try { Files.delete(f); } catch (IOException e) {
            throw new IllegalStateException("failed to delete dedup rules: " + f.toAbsolutePath(), e);
        }
        List<DedupRulesIndexItem> idx = readIndex();
        boolean wasActive = idx.stream().filter(i -> id.equals(i.id())).anyMatch(DedupRulesIndexItem::active);
        List<DedupRulesIndexItem> next = idx.stream().filter(i -> !id.equals(i.id())).toList();
        if (wasActive && !next.isEmpty()) {
            next = Stream.concat(
                    Stream.of(new DedupRulesIndexItem(next.get(0).id(), next.get(0).name(), next.get(0).version(), next.get(0).updatedAt(), true)),
                    next.stream().skip(1).map(i -> new DedupRulesIndexItem(i.id(), i.name(), i.version(), i.updatedAt(), false))
            ).toList();
            log.info("deleted active rule {}, fallback active -> {}", id, next.get(0).id());
        }
        writeIndex(next);
        log.info("dedup rule set deleted: {}", id);
    }

    // ---------- internals ----------

    private DedupRulesIndexItem toIndex(DedupRuleset r, boolean active) {
        return new DedupRulesIndexItem(r.id(), r.name(), r.version(), r.updatedAt(), active);
    }

    private List<String> normalizeSuffixes(List<String> list) {
        if (list == null) return List.of();
        List<String> out = list.stream().map(s -> s == null ? "" : s.trim())
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

    private Path rulesDir() {
        return Path.of(rulesDir).toAbsolutePath();
    }

    private static String safeFileName(String id) {
        return id.replaceAll("[^A-Za-z0-9\\-_]", "");
    }

    private void writeOne(DedupRuleset r) {
        Path dir = rulesDir();
        try {
            Files.createDirectories(dir);
            Path target = dir.resolve(safeFileName(r.id()) + ".json");
            Path tmp = dir.resolve(safeFileName(r.id()) + ".json.tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), r);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new IllegalStateException("failed to write dedup rules: " + r.id(), e);
        }
    }

    private List<DedupRulesIndexItem> readIndex() {
        Path f = rulesDir().resolve(INDEX_FILE);
        if (Files.notExists(f)) return List.of();
        try {
            return mapper.readValue(f.toFile(), new TypeReference<List<DedupRulesIndexItem>>() {});
        } catch (IOException e) {
            log.warn("dedup rules index corrupt, rebuilding from directory scan");
            List<DedupRulesIndexItem> rebuilt = rebuildIndex();
            writeIndex(rebuilt);
            return rebuilt;
        }
    }

    private List<DedupRulesIndexItem> rebuildIndex() {
        Path dir = rulesDir();
        try (Stream<Path> s = Files.list(dir)) {
            List<DedupRulesIndexItem> list = new ArrayList<>();
            boolean hasActive = false;
            List<Path> files = s.filter(p -> p.toString().endsWith(".json") && !INDEX_FILE.equals(p.getFileName().toString())).toList();
            for (Path p : files) {
                try {
                    DedupRuleset r = mapper.readValue(p.toFile(), DedupRuleset.class);
                    boolean active = !hasActive;
                    hasActive |= active;
                    list.add(toIndex(r, active));
                } catch (IOException e) {
                    log.warn("skip invalid dedup rules file: {}", p);
                }
            }
            return list;
        } catch (IOException e) {
            return List.of();
        }
    }

    private void writeIndex(List<DedupRulesIndexItem> idx) {
        Path dir = rulesDir();
        try {
            Files.createDirectories(dir);
            Path target = dir.resolve(INDEX_FILE);
            Path tmp = dir.resolve(INDEX_FILE + ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), idx);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new IllegalStateException("failed to write dedup rules index", e);
        }
    }
}
