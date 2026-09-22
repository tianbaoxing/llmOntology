package com.conca.ontology.dedup.service.pipeline;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Component;

import com.conca.ontology.dedup.model.DedupExactMode;
import com.conca.ontology.dedup.model.DedupLabelConfig;
import com.conca.ontology.dedup.model.DedupRuleset;

/**
 * 精确 key 归一化阶段（A-2）：
 *   规范化 name 仍不相同，但某些属性组合完全一致 → 判重。
 *
 * 按 DedupRuleset.labelConfig 中 exactModes 的配置动态执行 3 种组合模式（命中任一即可）：
 *   NAME_AUX     ：name 规范化后相同 + 辅助属性值相同（若都非空）
 *   TITLE_ONLY   ：title 规范化后相同（适用于 CulturalRelic 等 nameProp=title 的类）
 *   TITLE_DIMS   ：title 规范化后相同 + 尺寸/维度属性完全一致（至少 1 个维度属性双方都非空）
 *
 * MatchBucket.subStage 统一取值（与 DedupAlgorithmScore.SUB_* 常量对齐）：
 *   "exact-nameAux"      NAME_AUX
 *   "exact-relicTitle"   TITLE_ONLY
 *   "exact-relicTitleSize" TITLE_DIMS
 */
@Component
public class ExactKeyStage {

    private final NormalizeAliasStage normalize;

    public ExactKeyStage(NormalizeAliasStage normalize) {
        this.normalize = normalize;
    }

    /** 输入节点：同 label 内比较。props 全量属性（含 keyProp） */
    public record NodeProps(String nodeRef, String label, Map<String, Object> props,
                            long totalDegree, int propCount) {}

    /** subStage = DedupAlgorithmScore.SUB_EXACT_* 三选一 */
    public record MatchBucket(String patternMode, String subStage, List<NodeProps> nodes) {}

    public List<MatchBucket> run(List<NodeProps> nodes, DedupRuleset rules) {
        List<MatchBucket> out = new ArrayList<>();
        if (nodes.isEmpty()) return out;
        String label = nodes.get(0).label;
        DedupLabelConfig cfg = rules.labelConfig() == null ? null : rules.labelConfig().get(label);
        if (cfg == null) return out;
        List<DedupExactMode> modes = cfg.exactModes() == null ? List.of() : cfg.exactModes();
        if (modes.isEmpty()) return out;
        for (DedupExactMode m : modes) {
            switch (m) {
                case NAME_AUX -> out.addAll(modeNamePlusAux(nodes, label, cfg));
                case TITLE_ONLY -> out.addAll(modeTitleOnly(nodes, cfg));
                case TITLE_DIMS -> out.addAll(modeTitlePlusDims(nodes, cfg));
            }
        }
        return out;
    }

    /** NAME_AUX：name(=nameProp) 规范化 + 辅助属性一致性 */
    private List<MatchBucket> modeNamePlusAux(List<NodeProps> nodes, String label, DedupLabelConfig cfg) {
        Map<String, List<NodeProps>> buckets = new LinkedHashMap<>();
        String nameProp = cfg.nameProp() == null || cfg.nameProp().isBlank() ? "name" : cfg.nameProp();
        List<String> auxKeys = cfg.auxProps() == null ? List.of() : cfg.auxProps();
        for (NodeProps n : nodes) {
            Object rawName = n.props().get(nameProp);
            if (rawName == null) continue;
            String base = normalize.baseNormalize(String.valueOf(rawName));
            if (base.isEmpty()) continue;
            String auxKey = fingerprint(n.props(), auxKeys);
            String fullKey = base + "||" + auxKey;
            buckets.computeIfAbsent(fullKey, _k -> new ArrayList<>()).add(n);
        }
        List<MatchBucket> out = new ArrayList<>();
        for (List<NodeProps> grp : buckets.values()) {
            if (grp.size() >= 2) {
                out.add(new MatchBucket("name+aux", "exact-nameAux", List.copyOf(grp)));
            }
        }
        return out;
    }

    /** TITLE_ONLY：title(=nameProp) 规范化完全一致 */
    private List<MatchBucket> modeTitleOnly(List<NodeProps> nodes, DedupLabelConfig cfg) {
        Map<String, List<NodeProps>> buckets = new LinkedHashMap<>();
        String nameProp = cfg.nameProp() == null || cfg.nameProp().isBlank() ? "title" : cfg.nameProp();
        for (NodeProps n : nodes) {
            Object rawTitle = n.props().get(nameProp);
            if (rawTitle == null) continue;
            String norm = normalize.baseNormalize(String.valueOf(rawTitle));
            if (norm.isEmpty()) continue;
            buckets.computeIfAbsent(norm, _k -> new ArrayList<>()).add(n);
        }
        List<MatchBucket> out = new ArrayList<>();
        for (List<NodeProps> grp : buckets.values()) {
            if (grp.size() >= 2) {
                out.add(new MatchBucket("relic:title", "exact-relicTitle", List.copyOf(grp)));
            }
        }
        return out;
    }

    /** TITLE_DIMS：title 规范化 + 尺寸/维度属性一致（至少 1 个维度属性双方都有） */
    private List<MatchBucket> modeTitlePlusDims(List<NodeProps> nodes, DedupLabelConfig cfg) {
        Map<String, List<NodeProps>> buckets = new LinkedHashMap<>();
        String nameProp = cfg.nameProp() == null || cfg.nameProp().isBlank() ? "title" : cfg.nameProp();
        List<String> dimsList = cfg.dimProps() == null ? List.of() : cfg.dimProps();
        String[] DIMS = dimsList.toArray(new String[0]);
        for (NodeProps n : nodes) {
            Object rawTitle = n.props().get(nameProp);
            if (rawTitle == null) continue;
            String norm = normalize.baseNormalize(String.valueOf(rawTitle));
            if (norm.isEmpty()) continue;
            StringBuilder dims = new StringBuilder();
            for (String d : DIMS) {
                Object v = n.props().get(d);
                dims.append(d).append('=').append(v == null ? "?" : v.toString()).append(';');
            }
            String fullKey = norm + "||" + dims.toString();
            buckets.computeIfAbsent(fullKey, _k -> new ArrayList<>()).add(n);
        }
        List<MatchBucket> out = new ArrayList<>();
        for (List<NodeProps> grp : buckets.values()) {
            if (grp.size() >= 2) {
                boolean bothHaveSize = grp.stream().allMatch(np -> {
                    for (String d : DIMS) {
                        if (np.props().get(d) != null) return true;
                    }
                    return false;
                });
                if (bothHaveSize) {
                    out.add(new MatchBucket("relic:title+size", "exact-relicTitleSize", List.copyOf(grp)));
                }
            }
        }
        return out;
    }

    /** 辅助属性指纹：所有属性都缺失时返回 "none"（退化为纯 name 相同） */
    private String fingerprint(Map<String, Object> props, List<String> keys) {
        if (keys == null || keys.isEmpty()) return "none";
        List<String> vals = new ArrayList<>(keys.size());
        int nonNull = 0;
        for (String k : keys) {
            Object v = props.get(k);
            if (v != null && !String.valueOf(v).isBlank()) {
                nonNull++;
                vals.add(k + "=" + Objects.toString(v, ""));
            } else {
                vals.add(k + "=");
            }
        }
        if (nonNull == 0) return "none";
        return String.join("&", vals);
    }
}
