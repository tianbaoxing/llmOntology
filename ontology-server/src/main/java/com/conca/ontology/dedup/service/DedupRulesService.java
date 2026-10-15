package com.conca.ontology.dedup.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.conca.ontology.dedup.model.DedupExactMode;
import com.conca.ontology.dedup.model.DedupLabelConfig;
import com.conca.ontology.dedup.model.DedupRulesStore;
import com.conca.ontology.dedup.model.DedupRuleset;
import com.conca.ontology.dedup.model.DedupRulesIndexItem;
import com.conca.ontology.dedup.service.pipeline.NormalizeAliasStage;
import com.conca.ontology.graph.model.ClassDef;
import com.conca.ontology.graph.model.GraphSchema;
import com.conca.ontology.shared.web.ApiException;

import jakarta.annotation.PostConstruct;

/**
 * @author tianbaoxing
 * @date 2026-09-22
 */
@Service
public class DedupRulesService {

    private final DedupRulesStore store;
    private final GraphSchema schema;
    private final NormalizeAliasStage normalizeStage;

    public DedupRulesService(DedupRulesStore store, GraphSchema schema, NormalizeAliasStage normalizeStage) {
        this.store = store;
        this.schema = schema;
        this.normalizeStage = normalizeStage;
    }

    @PostConstruct
    public void init() {
        store.ensureInitialized();
    }

    // ---------- list / get / activate / delete ----------

    public List<DedupRulesIndexItem> listAll() {
        return store.listIndex();
    }

    public DedupRuleset get(String id) {
        return store.get(id);
    }

    public DedupRuleset getActive() {
        return store.getActive();
    }

    public DedupRuleset getOrDefault(String id) {
        if (id == null || id.isBlank()) return getActive();
        return store.get(id);
    }

    public List<DedupRulesIndexItem> setActive(String id) {
        store.setActive(id);
        return store.listIndex();
    }

    public List<DedupRulesIndexItem> delete(String id) {
        store.delete(id);
        return store.listIndex();
    }

    // ---------- save (with validation) ----------

    public DedupRulesIndexItem save(DedupRuleset input) {
        validate(input);
        return store.save(input);
    }

    private void validate(DedupRuleset r) {
        List<String> errors = new ArrayList<>();
        Set<String> knownLabels = new HashSet<>();
        for (ClassDef c : schema.allClasses()) knownLabels.add(c.name());

        if (r.name() == null || r.name().isBlank()) {
            errors.add("规则集名称不能为空");
        }
        if (r.labelConfig() != null) {
            for (Map.Entry<String, DedupLabelConfig> e : r.labelConfig().entrySet()) {
                String label = e.getKey();
                ClassDef cls = knownLabels.contains(label) ? schema.classOf(label) : null;
                if (cls == null) {
                    errors.add("Label '" + label + "' 未在 GraphSchema 中定义（提示：当前已知 label：" + knownLabels + "）。可先保存为空配置，待后续 Schema 新建类后再补充规则。");
                    continue;
                }
                Set<String> props = new HashSet<>();
                cls.properties().forEach(p -> props.add(p.name()));
                DedupLabelConfig cfg = e.getValue();
                if (cfg == null) continue;
                if (cfg.nameProp() != null && !props.contains(cfg.nameProp())) {
                    errors.add("Label '" + label + "' 的 name 属性源 '" + cfg.nameProp() + "' 不存在；可用属性：" + props);
                }
                if (cfg.auxProps() != null) {
                    for (String p : cfg.auxProps()) {
                        if (!props.contains(p)) {
                            errors.add("Label '" + label + "' 辅助属性 '" + p + "' 不存在；可用属性：" + props);
                        }
                    }
                }
                if (cfg.conflictProps() != null) {
                    for (String p : cfg.conflictProps()) {
                        if (!props.contains(p)) {
                            errors.add("Label '" + label + "' 冲突检测属性 '" + p + "' 不存在；可用属性：" + props);
                        }
                    }
                }
            }
        }
        if (r.suffixTable() != null) {
            for (String label : r.suffixTable().keySet()) {
                ClassDef cls = knownLabels.contains(label) ? schema.classOf(label) : null;
                if (cls == null) {
                    errors.add("suffixTable 中 label '" + label + "' 未在 GraphSchema 中定义（当前已知：" + knownLabels + "）");
                }
            }
        }
        if (r.globalConflictProps() != null) {
            for (String p : r.globalConflictProps()) {
                // global 属性可能跨类通用，所以不校验必须在某特定类存在；只校验命名格式
                if (!GraphSchema.isValidIdentifier(p)) {
                    errors.add("全局冲突属性 '" + p + "' 命名不合法（需字母开头的标识符）");
                }
            }
        }
        if (!errors.isEmpty()) {
            throw new ApiException(400, "规则校验失败：\n- " + String.join("\n- ", errors));
        }
    }

    // ---------- helpers: 供 DedupScanService 跨阶段使用的快捷读取 ----------

    public String namePropOf(DedupRuleset rules, String label) {
        DedupLabelConfig cfg = rules.labelConfig().get(label);
        if (cfg != null && cfg.nameProp() != null && !cfg.nameProp().isBlank()) {
            return cfg.nameProp();
        }
        ClassDef cls = schema.classOf(label);
        return cls == null ? "name" : cls.keyProp();
    }

    public List<String> conflictPropsOf(DedupRuleset rules, String label) {
        LinkedHashSet<String> s = new LinkedHashSet<>();
        if (rules.globalConflictProps() != null) s.addAll(rules.globalConflictProps());
        DedupLabelConfig cfg = rules.labelConfig().get(label);
        if (cfg != null && cfg.conflictProps() != null) s.addAll(cfg.conflictProps());
        return List.copyOf(s);
    }

    // ---------- preview ----------

    public record PreviewSample(String label, String name1, String name2,
                                Map<String, Object> props1, Map<String, Object> props2) {}
    public record PreviewNormStep(String raw, String base, List<String> keys) {}
    public record PreviewResult(
            boolean hit,
            List<String> reasons,
            PreviewNormStep norm1,
            PreviewNormStep norm2,
            List<String> sharedNormKeys,
            List<String> exactSubStagesHit) {}

    public PreviewResult preview(DedupRuleset rules, PreviewSample sample) {
        if (sample == null || sample.label() == null) {
            throw new ApiException(400, "预览缺少 label 参数");
        }
        if ((sample.name1() == null || sample.name1().isBlank()) || (sample.name2() == null || sample.name2().isBlank())) {
            throw new ApiException(400, "预览需要 name1 和 name2 两个非空样例字符串");
        }

        // normalize
        String base1 = normalizeStage.baseNormalize(sample.name1());
        String base2 = normalizeStage.baseNormalize(sample.name2());
        List<String> keys1 = generateNormKeysWithRules(rules, base1, sample.label());
        List<String> keys2 = generateNormKeysWithRules(rules, base2, sample.label());
        List<String> shared = keys1.stream().filter(keys2::contains).toList();
        boolean normHit = !shared.isEmpty();

        List<String> exactHits = new ArrayList<>();
        DedupLabelConfig cfg = rules.labelConfig().get(sample.label());
        if (cfg != null && cfg.exactModes() != null) {
            String nm1 = normalizeStage.baseNormalize(readProp(sample.props1(), cfg.nameProp(), sample.name1()));
            String nm2 = normalizeStage.baseNormalize(readProp(sample.props2(), cfg.nameProp(), sample.name2()));
            for (DedupExactMode m : cfg.exactModes()) {
                switch (m) {
                    case NAME_AUX -> {
                        String fp1 = fingerprint(sample.props1(), cfg.auxProps());
                        String fp2 = fingerprint(sample.props2(), cfg.auxProps());
                        if (!nm1.isEmpty() && nm1.equals(nm2) && fp1.equals(fp2)) exactHits.add("exact-nameAux");
                    }
                }
            }
        }

        boolean hit = normHit || !exactHits.isEmpty();
        List<String> reasons = new ArrayList<>();
        if (normHit) reasons.add("normalizeAlias 命中共同 normKey：" + shared);
        exactHits.forEach(e -> reasons.add("精确模式命中：" + e));
        if (!hit) reasons.add("未命中任何候选组（两字符串规范化后无共同 normKey，且精确匹配模式也未一致）");

        return new PreviewResult(
                hit, reasons,
                new PreviewNormStep(sample.name1(), base1, keys1),
                new PreviewNormStep(sample.name2(), base2, keys2),
                shared, exactHits);
    }

    // ---------- internals ----------

    private List<String> generateNormKeysWithRules(DedupRuleset rules, String base, String label) {
        if (base.isEmpty()) return List.of();
        List<String> suffixes = rules.suffixTable() == null ? List.of()
                : rules.suffixTable().getOrDefault(label, List.of());
        List<String> out = new ArrayList<>(4);
        out.add(base);
        String stripped = base;
        boolean changed;
        do {
            changed = false;
            for (String sfx : suffixes) {
                if (sfx == null || sfx.isEmpty()) continue;
                if (stripped.endsWith(sfx) && stripped.length() > sfx.length()) {
                    stripped = stripped.substring(0, stripped.length() - sfx.length()).trim();
                    changed = true;
                    if (!stripped.isEmpty() && !out.contains(stripped)) out.add(stripped);
                }
            }
        } while (changed);
        return List.copyOf(out);
    }

    private static String readProp(Map<String, Object> m, String key, String fallback) {
        if (m == null) return fallback;
        Object v = m.get(key);
        if (v == null) return fallback;
        String s = String.valueOf(v);
        return s.isBlank() ? fallback : s;
    }

    private static String fingerprint(Map<String, Object> props, List<String> keys) {
        if (keys == null || keys.isEmpty()) return "none";
        List<String> vals = new ArrayList<>(keys.size());
        int nonNull = 0;
        for (String k : keys) {
            Object v = props == null ? null : props.get(k);
            if (v != null && !String.valueOf(v).isBlank()) {
                nonNull++;
                vals.add(k + "=" + v);
            } else {
                vals.add(k + "=");
            }
        }
        if (nonNull == 0) return "none";
        return String.join("&", vals);
    }

    // 供 NormalizeAliasStage 注入 DedupRuleset 时复用 suffixes 解析（保持原有 generateNormKeys 结构不变，改造另在 Stage 中完成）
    public List<String> suffixesFor(DedupRuleset rules, String label) {
        if (rules == null || label == null) return List.of();
        List<String> s = rules.suffixTable() == null ? null : rules.suffixTable().get(label);
        return s == null ? List.of() : s;
    }
}
