package com.conca.ontology.dedup.store;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.conca.ontology.dedup.converter.DedupRulesetConverter;
import com.conca.ontology.dedup.entity.RuleDedup;
import com.conca.ontology.dedup.mapper.RuleDedupMapper;
import com.conca.ontology.dedup.model.DedupExactMode;
import com.conca.ontology.dedup.model.DedupLabelConfig;
import com.conca.ontology.dedup.model.DedupRulesIndexItem;
import com.conca.ontology.dedup.model.DedupRuleset;
import com.conca.ontology.shared.web.ApiException;

/**
 * 去重规则集存储（MySQL rule_dedup，P2 起，由 dedup-rules.json 单文件迁移而来）：
 *   全系统仅一套规则，持久化为单行，无索引；active 恒为 true。
 *   无行 = 尚未创建规则（前端显示空白模板，保存即新建）。
 *   保存保留乐观锁（version 不匹配 409）与 synchronized（读-改-写整体串行，保护逻辑不变）。
 *
 * @author tianbaoxing
 * @date 2026-09-22
 */
@Component
public class DedupRulesStore {

    private static final Logger log = LoggerFactory.getLogger(DedupRulesStore.class);
    private static final int MAX_SUFFIXES_PER_LABEL = 200;

    private final RuleDedupMapper ruleMapper;

    public DedupRulesStore(RuleDedupMapper ruleMapper) {
        this.ruleMapper = ruleMapper;
    }

    /** 单行模式：表由 Flyway 建，无需初始化（保留方法以兼容 DedupRulesService 启动调用） */
    public void ensureInitialized() {
    }

    public List<DedupRulesIndexItem> listIndex() {
        RuleDedup r = readOrNull();
        return r == null ? List.of() : List.of(toIndex(r));
    }

    public DedupRuleset get(String id) {
        return DedupRulesetConverter.toModel(require(id));
    }

    public DedupRuleset getActive() {
        return DedupRulesetConverter.toModel(require(null));
    }

    /** 单套规则恒为激活：仅校验存在性与 id 匹配，无状态可切换 */
    public void setActive(String id) {
        require(id);
    }

    @Transactional
    public synchronized DedupRulesIndexItem save(DedupRuleset input) {
        RuleDedup existing = readOrNull();
        if (existing != null && input.version() > 0 && existing.getVersion() != input.version()) {
            throw new ApiException(409, "规则已被他人修改（当前 v" + existing.getVersion() + "，提交 v" + input.version() + "），请刷新后重试");
        }
        // 单行模式：已有规则时沿用其 id；首次创建时用入参 id 或生成 UUID
        String id = existing != null ? existing.getId()
                : (input.id() == null || input.id().isBlank() ? UUID.randomUUID().toString() : input.id());
        if (id.chars().anyMatch(c -> !(Character.isLetterOrDigit(c) || c == '-' || c == '_'))) {
            throw new ApiException(400, "规则集 ID 仅允许字母、数字、-、_");
        }
        int version = existing != null ? existing.getVersion() + 1 : 1;
        String name = (input.name() == null || input.name().isBlank()) ? "去重规则" : input.name().trim();

        Map<String, List<String>> suffixTable = new LinkedHashMap<>(input.suffixTable() == null ? Map.of() : input.suffixTable());
        suffixTable.replaceAll((k, v) -> normalizeSuffixes(v));

        Map<String, DedupLabelConfig> labelConfig = new LinkedHashMap<>(input.labelConfig() == null ? Map.of() : input.labelConfig());
        labelConfig.replaceAll((k, v) -> normalizeLabelConfig(v));

        List<String> global = input.globalConflictProps() == null ? List.of()
                : input.globalConflictProps().stream().map(String::trim).filter(s -> !s.isBlank()).distinct().toList();

        RuleDedup next = new RuleDedup();
        next.setId(id);
        next.setName(name);
        next.setVersion(version);
        next.setUpdatedAt(System.currentTimeMillis());
        next.setSuffixTable(suffixTable);
        next.setLabelConfig(labelConfig);
        next.setGlobalConflictProps(global);
        if (existing == null) {
            ruleMapper.insert(next);
        } else {
            ruleMapper.updateById(next);
        }
        log.info("dedup rules saved: id={} name={} version={} (new={})", id, name, version, existing == null);
        return toIndex(next);
    }

    /** 删除唯一一套规则 → 回到「尚未创建」状态（下次保存即新建） */
    @Transactional
    public synchronized void delete(String id) {
        RuleDedup r = require(id);
        ruleMapper.deleteById(r.getId());
        log.info("dedup rule set deleted: {}", r.getId());
    }

    // ---------- internals ----------

    private static DedupRulesIndexItem toIndex(RuleDedup r) {
        return new DedupRulesIndexItem(r.getId(), r.getName(), r.getVersion(), r.getUpdatedAt(), true);
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
        List<String> conf = v.conflictProps() == null ? List.of() : v.conflictProps().stream().map(String::trim).filter(s -> !s.isBlank()).distinct().toList();
        return new DedupLabelConfig(nameProp, auxProps, modes, conf);
    }

    private RuleDedup readOrNull() {
        return ruleMapper.selectList(null).stream().findFirst().orElse(null);
    }

    private RuleDedup require(String id) {
        RuleDedup r = readOrNull();
        if (r == null) {
            throw new ApiException(404, "尚未创建去重规则集，请先在「去重规则配置」页面创建并保存一套规则。");
        }
        if (id != null && !id.isBlank() && !r.getId().equals(id)) {
            throw new ApiException(404, "单文件模式仅有一套规则（id=" + r.getId() + "），不存在: " + id);
        }
        return r;
    }
}
