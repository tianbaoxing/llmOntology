package com.conca.ontology.dedup.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.zip.CRC32;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.conca.ontology.dedup.dto.DedupScanDTO;
import com.conca.ontology.dedup.dto.DedupScanResultDTO;
import com.conca.ontology.dedup.dto.DedupAlgorithmScoreDTO;
import com.conca.ontology.dedup.dto.DupCandidateDTO;
import com.conca.ontology.dedup.dto.DupCandidateGroupDTO;
import com.conca.ontology.dedup.dto.DupCandidateGroupDTO.ConflictLevel;
import com.conca.ontology.dedup.dto.DedupRulesetDTO;
import com.conca.ontology.dedup.service.pipeline.ExactKeyStage;
import com.conca.ontology.dedup.service.pipeline.ExactKeyStage.NodeProps;
import com.conca.ontology.dedup.service.pipeline.NormalizeAliasStage;
import com.conca.ontology.dedup.service.pipeline.NormalizeAliasStage.NodeName;
import com.conca.ontology.graph.dto.QueryResultDTO;
import com.conca.ontology.graph.model.ClassDef;
import com.conca.ontology.graph.model.GraphSchema;
import com.conca.ontology.graph.service.GraphQueryService;
import com.conca.ontology.shared.web.ApiException;

/**
 * 去重扫描服务（M1）：
 *   - 调度 NormalizeAliasStage（规范化别名）+ ExactKeyStage（精确 key 组合）
 *   - Neo4j 节点数据经 GraphQueryService（只读红线保护）拉取
 *   - 阶段结果合并去重 → 生成 DupCandidateGroupDTO 列表 + KPI 汇总
 *   - ConflictGuard 属性冲突检测：全局冲突属性 + 类级冲突属性并集检测
 *   - 规则集快照：每次 scan 从 DedupRulesService 取不可变 DedupRulesetDTO，跨线程安全
 *
 * @author tianbaoxing
 * @date 2026-09-21
 */
@Service
public class DedupScanService {

    private final GraphQueryService qs;
    private final GraphSchema schema;
    private final NormalizeAliasStage normStage;
    private final ExactKeyStage exactStage;
    private final DedupRulesService rulesService;

    /** 批量拉取节点时，单 label 的节点数硬上限（避免万级节点全量拉卡死；0=不限制） */
    private final int perLabelNodeLimit;

    public DedupScanService(GraphQueryService qs, GraphSchema schema,
                            NormalizeAliasStage normStage, ExactKeyStage exactStage,
                            DedupRulesService rulesService,
                            @Value("${graph.dedup.per-label-node-limit:0}") int perLabelNodeLimit) {
        this.qs = qs;
        this.schema = schema;
        this.normStage = normStage;
        this.exactStage = exactStage;
        this.rulesService = rulesService;
        this.perLabelNodeLimit = perLabelNodeLimit;
    }

    // ============================================================
    // 对外主入口
    // ============================================================

    public DedupScanResultDTO scan(DedupScanDTO req) {
        long startMs = System.currentTimeMillis();
        double threshold = req.thresholdSafe();
        String scanLabel = req.labelSafe();

        // 规则快照：本次 scan 全程使用同一份不可变 rules（CopyOnRead，并发 save 不影响进行中扫描）
        final DedupRulesetDTO rules = rulesService.getOrDefault(req.ruleSetId());

        Collection<ClassDef> classes;
        if ("*".equals(scanLabel)) {
            classes = schema.allClasses();
        } else {
            ClassDef c = schema.classOf(scanLabel);
            if (c == null) throw new ApiException(400, "unknown label: " + scanLabel);
            classes = List.of(c);
        }

        List<LabelRaw> allRaws = new ArrayList<>();
        long scannedNodes = 0;
        for (ClassDef c : classes) {
            LabelRaw raw = pullLabelNodes(c);
            scannedNodes += raw.nodes.size();
            allRaws.add(raw);
        }

        List<DupCandidateGroupDTO> mergedGroups = new ArrayList<>();
        for (LabelRaw raw : allRaws) {
            List<DupCandidateGroupDTO> g1 = runNormalizeAlias(raw, rules);
            List<DupCandidateGroupDTO> g2 = runExactKey(raw, rules);
            mergedGroups.addAll(mergeGroupResults(raw, g1, g2, threshold, rules));
        }

        mergedGroups.sort(Comparator.comparingDouble((DupCandidateGroupDTO g) -> g.maxComposite()).reversed()
                .thenComparingInt(DupCandidateGroupDTO::size).reversed()
                .thenComparingInt(DupCandidateGroupDTO::multiHitCount).reversed()
                .thenComparing(DupCandidateGroupDTO::label));

        long totalGroups = mergedGroups.size();
        long autoReady = 0, manual = 0, blocked = 0, saved = 0;
        for (DupCandidateGroupDTO g : mergedGroups) {
            saved += g.estimatedSaved();
            if (g.conflictLevel() == ConflictLevel.DISJOINT) blocked++;
            else if (g.autoMergeReady()) autoReady++;
            else manual++;
        }

        int skip = req.skipGroupsSafe();
        int limit = req.limitGroupsSafe();
        List<DupCandidateGroupDTO> page;
        boolean hasMore;
        if (limit > 0) {
            int from = Math.min(skip, mergedGroups.size());
            int to = Math.min(from + limit, mergedGroups.size());
            page = mergedGroups.subList(from, to);
            hasMore = to < mergedGroups.size();
        } else {
            page = mergedGroups;
            hasMore = false;
        }

        long endMs = System.currentTimeMillis();
        return new DedupScanResultDTO(
                startMs, endMs, endMs - startMs,
                scanLabel, scannedNodes, classes.size(),
                threshold, totalGroups, autoReady, manual, blocked, saved,
                page, hasMore);
    }

    // ============================================================
    // Neo4j 拉节点
    // ============================================================

    private record LabelRaw(String label, String keyProp, List<RawNode> nodes) {}

    private record RawNode(String eid, Map<String, Object> props, long totalDegree,
                           int propCount) {}

    @SuppressWarnings("unchecked")
    private LabelRaw pullLabelNodes(ClassDef def) {
        String label = def.name();
        String keyProp = def.keyProp();
        StringBuilder cypher = new StringBuilder(256);
        cypher.append("MATCH (n:`").append(label).append("`) ");
        cypher.append("WITH n, elementId(n) AS eid, labels(n)[0] AS lbl, properties(n) AS props, ");
        cypher.append("COUNT { (n)-[]->() } AS outD, COUNT { ()-[]->(n) } AS inD ");
        cypher.append("RETURN eid, lbl, props, (outD + inD) AS totalDeg ");
        cypher.append("ORDER BY eid");
        if (perLabelNodeLimit > 0) cypher.append(" LIMIT ").append(perLabelNodeLimit);
        QueryResultDTO qr = qs.query(cypher.toString(), Map.of());
        List<RawNode> nodes = new ArrayList<>(qr.rows().size());
        for (Map<String, Object> r : qr.rows()) {
            String eid = String.valueOf(r.get("eid"));
            Map<String, Object> props = (Map<String, Object>) r.get("props");
            if (props == null) props = Map.of();
            long deg = ((Number) r.getOrDefault("totalDeg", 0L)).longValue();
            nodes.add(new RawNode(eid, props, deg, props.size()));
        }
        return new LabelRaw(label, keyProp, nodes);
    }

    // ============================================================
    // 阶段调度
    // ============================================================

    private List<DupCandidateGroupDTO> runNormalizeAlias(LabelRaw raw, DedupRulesetDTO rules) {
        String nameProp = rulesService.namePropOf(rules, raw.label);
        List<NodeName> inputs = new ArrayList<>(raw.nodes.size());
        for (RawNode rn : raw.nodes) {
            Object v = rn.props.get(nameProp);
            if (v == null) {
                v = rn.props.getOrDefault(raw.keyProp, "");
            }
            inputs.add(new NodeName(rn.eid, raw.label, String.valueOf(v),
                    rn.totalDegree, rn.propCount));
        }
        List<NormalizeAliasStage.MatchBucket> buckets = normStage.run(inputs, rules);
        List<DupCandidateGroupDTO> out = new ArrayList<>(buckets.size());
        for (NormalizeAliasStage.MatchBucket b : buckets) {
            out.add(buildGroupFromNodeRefs(raw, dedupeRefs(b.nodes().stream().map(NodeName::nodeRef).toList()),
                    "normalizeAlias", DedupAlgorithmScoreDTO.exactNormalizeOnly(), rules));
        }
        return out;
    }

    private List<DupCandidateGroupDTO> runExactKey(LabelRaw raw, DedupRulesetDTO rules) {
        List<NodeProps> inputs = new ArrayList<>(raw.nodes.size());
        for (RawNode rn : raw.nodes) {
            inputs.add(new NodeProps(rn.eid, raw.label, rn.props, rn.totalDegree, rn.propCount));
        }
        List<ExactKeyStage.MatchBucket> buckets = exactStage.run(inputs, rules);
        List<DupCandidateGroupDTO> out = new ArrayList<>(buckets.size());
        for (ExactKeyStage.MatchBucket b : buckets) {
            out.add(buildGroupFromNodeRefs(raw, dedupeRefs(b.nodes().stream().map(NodeProps::nodeRef).toList()),
                    "exactKey", DedupAlgorithmScoreDTO.exactKeyOnly(b.subStage()), rules));
        }
        return out;
    }

    /** 去重：同 bucket 内同 nodeRef 可能出现多次（比如 name 产生了两个 normKey 都命中同组） */
    private List<String> dedupeRefs(List<String> refs) {
        LinkedHashSet<String> set = new LinkedHashSet<>(refs);
        if (set.size() < 2) return List.of();
        return new ArrayList<>(set);
    }

    // ============================================================
    // 合并阶段结果（跨阶段去重同一组节点）
    // ============================================================

    private List<DupCandidateGroupDTO> mergeGroupResults(LabelRaw raw,
            List<DupCandidateGroupDTO> g1, List<DupCandidateGroupDTO> g2, double threshold,
            DedupRulesetDTO rules) {
        Map<String, GroupBuilder> builders = new LinkedHashMap<>();
        for (DupCandidateGroupDTO g : g1) absorb(builders, g);
        for (DupCandidateGroupDTO g : g2) absorb(builders, g);

        List<DupCandidateGroupDTO> out = new ArrayList<>(builders.size());
        for (GroupBuilder b : builders.values()) {
            if (b.composite < threshold || b.eids == null || b.eids.size() < 2) continue;
            DupCandidateGroupDTO finalGroup = finalize(raw, b, rules);
            if (finalGroup != null) out.add(finalGroup);
        }
        return out;
    }

    private static final class GroupBuilder {
        List<String> eids;
        Set<String> hitStages = new LinkedHashSet<>();
        double bestNorm = 0.0, bestExact = 0.0;
        double composite = 0.0;
        List<DupCandidateDTO> candidates; // 在 absorb 第一次被调用时初始化占位，finalize 时重算
        Map<String, DedupAlgorithmScoreDTO> perNodeScore = new LinkedHashMap<>();
    }

    private void absorb(Map<String, GroupBuilder> map, DupCandidateGroupDTO g) {
        if (g.candidates().isEmpty()) return;
        List<String> eids = g.candidates().stream().map(DupCandidateDTO::eid).sorted().toList();
        if (eids.size() < 2) return;
        String key = groupKey(eids);
        GroupBuilder b = map.get(key);
        if (b == null) {
            b = new GroupBuilder();
            b.eids = eids;
            map.put(key, b);
        }
        b.hitStages.addAll(g.hitStages());
        for (DupCandidateDTO c : g.candidates()) {
            DedupAlgorithmScoreDTO s = c.score();
            DedupAlgorithmScoreDTO prev = b.perNodeScore.get(c.eid());
            // 跨阶段命中：用 merge（位或合并子策略命中，取各维分最大值，合并 hitStages 并集）
            b.perNodeScore.put(c.eid(), prev == null ? s : prev.merge(s));
            b.bestNorm  = Math.max(b.bestNorm,  s.normalizeAlias());
            b.bestExact = Math.max(b.bestExact, s.exactKey());
        }
        double c0 = Math.max(b.bestNorm, b.bestExact);
        double composite = b.bestNorm * 0.4 + b.bestExact * 0.4 + 0.0 * 0.2;
        b.composite = c0 >= 0.999 ? 1.0 : composite;
    }

    private String groupKey(List<String> sortedEids) {
        StringBuilder sb = new StringBuilder(64);
        for (String s : sortedEids) sb.append(s).append('|');
        byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        CRC32 crc = new CRC32();
        crc.update(bytes);
        return sortedEids.size() + "-" + Long.toHexString(crc.getValue());
    }

    // ============================================================
    // 最终组装 DupCandidateGroupDTO（含 survivor 优先级 + 冲突标记 + 组级子策略聚合）
    // ============================================================

    private DupCandidateGroupDTO buildGroupFromNodeRefs(LabelRaw raw, List<String> eidRefs,
                                                      String stageName, DedupAlgorithmScoreDTO baseScore,
                                                      DedupRulesetDTO rules) {
        if (eidRefs.size() < 2) return null;
        Map<String, RawNode> byEid = new LinkedHashMap<>();
        for (RawNode rn : raw.nodes) byEid.put(rn.eid, rn);
        GroupBuilder gb = new GroupBuilder();
        gb.eids = new ArrayList<>(eidRefs);
        gb.hitStages.add(stageName);
        gb.bestNorm = baseScore.normalizeAlias();
        gb.bestExact = baseScore.exactKey();
        gb.composite = baseScore.composite();
        for (String eid : eidRefs) gb.perNodeScore.put(eid, baseScore);
        return finalize(raw, gb, rules);
    }

    private DupCandidateGroupDTO finalize(LabelRaw raw, GroupBuilder b,
                                       DedupRulesetDTO rules) {
        Map<String, RawNode> byEid = new LinkedHashMap<>();
        for (RawNode rn : raw.nodes) byEid.put(rn.eid, rn);

        int groupMask = 0;
        List<DupCandidateDTO> cands = new ArrayList<>(b.eids.size());
        for (String eid : b.eids) {
            RawNode rn = byEid.get(eid);
            if (rn == null) continue;
            Object keyVal = rn.props.getOrDefault(raw.keyProp, "");
            DedupAlgorithmScoreDTO sc = b.perNodeScore.get(eid);
            if (sc == null) {
                int mask = (b.bestNorm  > 0 ? DedupAlgorithmScoreDTO.MASK_NORM : 0)
                         | (b.bestExact > 0 ? DedupAlgorithmScoreDTO.MASK_EXACT_NAME_AUX : 0);
                sc = new DedupAlgorithmScoreDTO(
                        b.bestNorm, b.bestExact, 0.0, 0.0, 0.0, b.composite,
                        List.copyOf(b.hitStages),
                        (mask & DedupAlgorithmScoreDTO.MASK_NORM) != 0,
                        (mask & DedupAlgorithmScoreDTO.MASK_EXACT_NAME_AUX) != 0,
                        mask);
            }
            groupMask |= sc.subStageMask();
            cands.add(new DupCandidateDTO(eid, raw.label, raw.keyProp, String.valueOf(keyVal),
                    rn.props, rn.totalDegree, rn.propCount, -1, "", sc));
        }
        if (cands.size() < 2) return null;

        cands.sort((a, c) -> {
            int d = Integer.compare(c.propCount(), a.propCount());
            if (d != 0) return d;
            d = Long.compare(c.totalDegree(), a.totalDegree());
            if (d != 0) return d;
            d = Integer.compare(c.keyValue().length(), a.keyValue().length());
            if (d != 0) return d;
            return a.eid().compareTo(c.eid());
        });
        List<DupCandidateDTO> ordered = new ArrayList<>(cands.size());
        for (int i = 0; i < cands.size(); i++) {
            DupCandidateDTO c = cands.get(i);
            String reason = "";
            if (i == 0) {
                List<String> parts = new ArrayList<>(3);
                if (c.propCount() > 0) {
                    int maxOther = cands.stream().skip(1).mapToInt(DupCandidateDTO::propCount).max().orElse(-1);
                    if (c.propCount() > maxOther) parts.add("属性数最多(" + c.propCount() + ")");
                }
                if (c.totalDegree() > 0) {
                    long maxOther = cands.stream().skip(1).mapToLong(DupCandidateDTO::totalDegree).max().orElse(-1L);
                    if (c.totalDegree() > maxOther) parts.add("关联度最高(" + c.totalDegree() + ")");
                }
                if (parts.isEmpty()) parts.add("默认优先级（首元素）");
                reason = String.join("，", parts);
            }
            ordered.add(new DupCandidateDTO(c.eid(), c.label(), c.keyProp(), c.keyValue(),
                    c.props(), c.totalDegree(), c.propCount(), i, reason, c.score()));
        }

        // 属性冲突：全局 + 类级 并集（动态，取代硬编码 CRITICAL）
        List<String> critical = rulesService.conflictPropsOf(rules, raw.label);
        ConflictLevel cl = ConflictLevel.NONE;
        String clReason = "";
        for (String cp : critical) {
            Set<Object> seen = new LinkedHashSet<>();
            for (DupCandidateDTO c : ordered) {
                Object v = c.props().get(cp);
                if (v != null && !String.valueOf(v).isBlank()) seen.add(v);
            }
            if (seen.size() >= 2) {
                cl = ConflictLevel.ATTRIBUTE;
                clReason = "关键属性冲突：" + cp + " 存在多个不同值（"
                        + seen.stream().map(Objects::toString).limit(3).reduce((x, y) -> x + " / " + y).orElse("")
                        + (seen.size() > 3 ? " 等" : "") + "）";
                break;
            }
        }

        List<String> subStages = new ArrayList<>(2);
        if ((groupMask & DedupAlgorithmScoreDTO.MASK_NORM) != 0)              subStages.add(DedupAlgorithmScoreDTO.SUB_NORM);
        if ((groupMask & DedupAlgorithmScoreDTO.MASK_EXACT_NAME_AUX) != 0)    subStages.add(DedupAlgorithmScoreDTO.SUB_EXACT_NAME_AUX);

        double minC = ordered.stream().mapToDouble(c -> c.score().composite()).min().orElse(b.composite);
        double maxC = ordered.stream().mapToDouble(c -> c.score().composite()).max().orElse(b.composite);
        boolean autoReady = cl == ConflictLevel.NONE && minC >= 0.95;
        String gid = "G-" + groupKey(b.eids);
        int saved = ordered.size() - 1;
        return new DupCandidateGroupDTO(
                gid, ordered.size(), raw.label, minC, maxC, cl, clReason, autoReady,
                List.copyOf(b.hitStages), List.copyOf(ordered), saved,
                List.copyOf(subStages), subStages.size());
    }
}
