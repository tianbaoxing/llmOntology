package com.conca.ontology.graph.disjoint.service;
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
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.conca.ontology.graph.disjoint.dto.view.DisjointConflictView;
import com.conca.ontology.shared.web.ApiException;
import com.conca.ontology.graph.disjoint.model.DisjointRule;
import com.conca.ontology.graph.disjoint.model.DisjointRuleFile;

@Service
public class DisjointRuleService {

    private static final Logger log = LoggerFactory.getLogger(DisjointRuleService.class);
    private static final Pattern BRACKET = Pattern.compile("【([^】]+)】");

    private final ObjectMapper mapper;

    @Value("${graph.disjoint-rules-file:data/disjoint-rules.json}")
    private String disjointRulesFile;

    public DisjointRuleService(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public DisjointRuleFile loadAll() {
        Path file = filePath();
        if (Files.notExists(file)) {
            return new DisjointRuleFile(0, List.of());
        }
        return read(file);
    }

    public DisjointRule get(String id) {
        return loadAll().rules().stream()
                .filter(r -> id.equals(r.id()))
                .findFirst()
                .orElseThrow(() -> new ApiException(404, "规则不存在: " + id));
    }

    public synchronized DisjointRuleFile save(DisjointRule rule) {
        if (rule.id() == null || rule.id().isBlank()) {
            throw new ApiException(400, "规则 ID 不能为空");
        }
        if (rule.text() == null || rule.text().isBlank()) {
            throw new ApiException(400, "规则内容不能为空");
        }
        List<String> items = parseItems(rule.text());
        if (items.size() < 2) {
            throw new ApiException(400, "规则至少需要 2 个候选项（用【】包裹，如：不能同时是【青铜】且【瓷】）");
        }
        Path file = filePath();
        DisjointRuleFile pf = Files.exists(file) ? read(file) : new DisjointRuleFile(0, List.of());
        List<DisjointRule> rules = new ArrayList<>(pf.rules());
        int idx = -1;
        for (int i = 0; i < rules.size(); i++) {
            if (rule.id().equals(rules.get(i).id())) { idx = i; break; }
        }
        if (idx >= 0) rules.set(idx, rule);
        else rules.add(rule);
        DisjointRuleFile next = new DisjointRuleFile(pf.version() + 1, rules);
        write(file, next);
        log.info("disjoint rule saved: {} (version {}, {} items parsed)", rule.id(), next.version(), items.size());
        return next;
    }

    public synchronized DisjointRuleFile delete(String id) {
        Path file = filePath();
        DisjointRuleFile pf = Files.exists(file) ? read(file) : new DisjointRuleFile(0, List.of());
        List<DisjointRule> rules = new ArrayList<>(pf.rules());
        boolean removed = rules.removeIf(r -> id.equals(r.id()));
        if (!removed) throw new ApiException(404, "规则不存在: " + id);
        DisjointRuleFile next = new DisjointRuleFile(pf.version() + 1, rules);
        write(file, next);
        log.info("disjoint rule deleted: {} (version {})", id, next.version());
        return next;
    }

    /** 从【】中解析候选项 */
    public static List<String> parseItems(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        Matcher m = BRACKET.matcher(text);
        while (m.find()) {
            String s = m.group(1).trim();
            if (!s.isBlank() && !out.contains(s)) out.add(s);
        }
        return out;
    }

    /** 生成下一个自增 ID：R + 三位补零 */
    public String nextId() {
        DisjointRuleFile all = loadAll();
        int max = 0;
        for (DisjointRule r : all.rules()) {
            String id = r.id();
            if (id != null && id.length() > 1 && id.startsWith("R")) {
                try { max = Math.max(max, Integer.parseInt(id.substring(1))); }
                catch (NumberFormatException ignore) {}
            }
        }
        return "R" + String.format("%03d", max + 1);
    }

    /** 对单段文本执行冲突检测：逐条规则扫描，返回所有命中 ≥2 候选项的冲突。 */
    public List<DisjointConflictView> checkConflicts(String text) {
        List<DisjointConflictView> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;
        for (DisjointRule r : loadAll().rules()) {
            List<String> items = parseItems(r.text());
            if (items.size() < 2) continue;
            List<String> hits = new ArrayList<>();
            for (String it : items) {
                if (text.contains(it)) hits.add(it);
            }
            if (hits.size() >= 2) {
                out.add(new DisjointConflictView(r.id(), r.text(), hits));
            }
        }
        return out;
    }

    private DisjointRuleFile read(Path file) {
        try {
            return mapper.readValue(file.toFile(), DisjointRuleFile.class);
        } catch (Exception e) {
            throw new IllegalStateException("failed to load disjoint rules file: " + file.toAbsolutePath(), e);
        }
    }

    private void write(Path file, DisjointRuleFile next) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), next);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            throw new IllegalStateException("failed to save disjoint rules file: " + file.toAbsolutePath(), e);
        }
    }

    private Path filePath() {
        return Path.of(disjointRulesFile).toAbsolutePath();
    }
}
