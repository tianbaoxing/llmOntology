package com.conca.ontology.dedup.service.pipeline;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Component;

import com.conca.ontology.dedup.dto.DedupExactMode;
import com.conca.ontology.dedup.dto.DedupLabelConfigDTO;
import com.conca.ontology.dedup.dto.DedupRulesetDTO;

/**
 * 精确 key 归一化阶段（A-2）：
 *   规范化 name 仍不相同，但某些属性组合完全一致 → 判重。
 *
 * 按 DedupRulesetDTO.labelConfig 中 exactModes 的配置动态执行：
 *   NAME_AUX：name 规范化后相同 + 辅助属性值相同（若都非空）
 *
 * MatchBucket.subStage 统一取值（与 DedupAlgorithmScoreDTO.SUB_* 常量对齐）：
 *   "exact-nameAux"  NAME_AUX
 *
 * @author tianbaoxing
 * @date 2026-09-21
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

    /** subStage = DedupAlgorithmScoreDTO.SUB_EXACT_* 三选一 */
    public record MatchBucket(String patternMode, String subStage, List<NodeProps> nodes) {}

    public List<MatchBucket> run(List<NodeProps> nodes, DedupRulesetDTO rules) {
        List<MatchBucket> out = new ArrayList<>();
        if (nodes.isEmpty()) return out;
        String label = nodes.get(0).label;
        DedupLabelConfigDTO cfg = rules.labelConfig() == null ? null : rules.labelConfig().get(label);
        if (cfg == null) return out;
        List<DedupExactMode> modes = cfg.exactModes() == null ? List.of() : cfg.exactModes();
        if (modes.isEmpty()) return out;
        for (DedupExactMode m : modes) {
            switch (m) {
                case NAME_AUX -> out.addAll(modeNamePlusAux(nodes, label, cfg));
            }
        }
        return out;
    }

    /** NAME_AUX：name(=nameProp) 规范化 + 辅助属性一致性 */
    private List<MatchBucket> modeNamePlusAux(List<NodeProps> nodes, String label, DedupLabelConfigDTO cfg) {
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
