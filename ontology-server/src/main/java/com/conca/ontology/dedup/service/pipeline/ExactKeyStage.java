package com.conca.ontology.dedup.service.pipeline;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Component;

/**
 * 精确 key 归一化阶段（A-2）：
 *   规范化 name 仍不相同，但某些属性组合完全一致 → 判重。
 *
 * M1 按 label 不同支持 3 种组合模式（命中任一即可）：
 *   模式①（所有 name-key 概念层类通用：Era/Category/Material/CollectionSource/Theme/
 *          Place/HistoricalFigure/HistoricalEvent/AncientText）：
 *        name 规范化后相同 + 关键辅助属性值相同（若都非空）
 *          Place       → 都有 province 时需相同
 *          HistoricalFigure → 都有 dynastyLabel 时需相同
 *          HistoricalEvent  → 都有 startYear 时需相同（endYear 可选）
 *   模式② CulturalRelic（实例层文物，key=itemId，名称=title）：
 *        title 规范化后相同
 *   模式③ CulturalRelic 强模式（需多值都非空）：
 *        title 规范化后相同 + 尺寸（heightCm 或 widthCm 或 lengthCm 或 diameterCm 任一组合）完全相同
 *
 * 以上 3 种模式任一命中 → exactKey 判定通过。
 *
 * MatchBucket.subStage 统一取值（与 DedupAlgorithmScore.SUB_* 常量对齐）：
 *   "exact-nameAux"      模式①
 *   "exact-relicTitle"   模式②
 *   "exact-relicTitleSize" 模式③
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

    public List<MatchBucket> run(List<NodeProps> nodes) {
        List<MatchBucket> out = new ArrayList<>();
        if (nodes.isEmpty()) return out;
        String label = nodes.get(0).label;
        out.addAll(modeNamePlusAux(nodes, label));
        if ("CulturalRelic".equals(label)) {
            out.addAll(modeRelicTitle(nodes));
            out.addAll(modeRelicTitlePlusSize(nodes));
        }
        return out;
    }

    /** 模式①：name 或类 keyProp(=name) 规范化 + 辅助属性一致性 */
    private List<MatchBucket> modeNamePlusAux(List<NodeProps> nodes, String label) {
        Map<String, List<NodeProps>> buckets = new LinkedHashMap<>();
        for (NodeProps n : nodes) {
            Object rawName = n.props.get("name");
            if (rawName == null) continue;
            String base = normalize.baseNormalize(String.valueOf(rawName));
            if (base.isEmpty()) continue;
            String auxKey = auxFingerprint(label, n.props);
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

    /** 模式② CulturalRelic: title 规范化完全一致 */
    private List<MatchBucket> modeRelicTitle(List<NodeProps> nodes) {
        Map<String, List<NodeProps>> buckets = new LinkedHashMap<>();
        for (NodeProps n : nodes) {
            Object rawTitle = n.props.get("title");
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

    /** 模式③ CulturalRelic: title 规范化 + 尺寸属性一致（至少 1 个尺寸属性双方都有） */
    private List<MatchBucket> modeRelicTitlePlusSize(List<NodeProps> nodes) {
        Map<String, List<NodeProps>> buckets = new LinkedHashMap<>();
        final String[] DIMS = {"heightCm", "widthCm", "lengthCm", "boreCm", "footCm", "diameterCm", "weightG"};
        for (NodeProps n : nodes) {
            Object rawTitle = n.props.get("title");
            if (rawTitle == null) continue;
            String norm = normalize.baseNormalize(String.valueOf(rawTitle));
            if (norm.isEmpty()) continue;
            StringBuilder dims = new StringBuilder();
            for (String d : DIMS) {
                Object v = n.props.get(d);
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
                        if (np.props.get(d) != null) return true;
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

    /** 不同 label 的辅助属性指纹（所有属性都缺失时返回 "none"，退化为纯 name 相同） */
    private String auxFingerprint(String label, Map<String, Object> props) {
        return switch (label) {
            case "Place"            -> fp(props, "province", "kind");
            case "HistoricalFigure" -> fp(props, "dynastyLabel", "identity");
            case "HistoricalEvent"  -> fp(props, "startYear", "endYear");
            case "AncientText"      -> fp(props, "category");
            default                 -> "none";
        };
    }

    /** 生成属性组的指纹：当且仅当所有属性都非空时取其拼接；否则返回 empty（即空指纹不参与判定，避免单方有值一方缺被误判） */
    private String fp(Map<String, Object> props, String... keys) {
        List<String> vals = new ArrayList<>(keys.length);
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
