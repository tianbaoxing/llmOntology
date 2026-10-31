package com.conca.ontology.disjoint.service;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.conca.ontology.disjoint.dto.DisjointConflictDTO;
import com.conca.ontology.disjoint.dto.DisjointRule;
import com.conca.ontology.disjoint.dto.DisjointRulesDTO;
import com.conca.ontology.disjoint.entity.RuleDisjoint;
import com.conca.ontology.disjoint.mapper.RuleDisjointMapper;
import com.conca.ontology.shared.web.ApiException;

/**
 * 互斥约束词典：MySQL rule_disjoint 多行表为唯一事实源
 * （P2 起由 disjoint-rules.json 迁移而来，每条规则一行，无全局 version）。
 * 按规则 ID upsert；规则 ID 字典序即展示序（原文件内声明顺序 R001..Rnnn）。
 *
 * @author tianbaoxing
 * @date 2026-09-11
 */
@Service
public class DisjointRuleService {

    private static final Logger log = LoggerFactory.getLogger(DisjointRuleService.class);
    private static final Pattern BRACKET = Pattern.compile("【([^】]+)】");

    private final RuleDisjointMapper ruleMapper;

    public DisjointRuleService(RuleDisjointMapper ruleMapper) {
        this.ruleMapper = ruleMapper;
    }

    public DisjointRulesDTO loadAll() {
        return new DisjointRulesDTO(listOrdered());
    }

    public DisjointRule get(String id) {
        RuleDisjoint row = ruleMapper.selectById(id);
        if (row == null) {
            throw new ApiException(404, "规则不存在: " + id);
        }
        return new DisjointRule(row.getId(), row.getText());
    }

    @Transactional
    public DisjointRulesDTO save(DisjointRule rule) {
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
        RuleDisjoint existing = ruleMapper.selectById(rule.id());
        RuleDisjoint row = existing != null ? existing : new RuleDisjoint();
        if (existing == null) {
            row.setId(rule.id());
        }
        row.setText(rule.text());
        row.setUpdateTime(LocalDateTime.now());
        if (existing == null) {
            ruleMapper.insert(row);
        } else {
            ruleMapper.updateById(row);
        }
        log.info("disjoint rule saved: {} ({} items parsed)", rule.id(), items.size());
        return loadAll();
    }

    @Transactional
    public DisjointRulesDTO delete(String id) {
        if (ruleMapper.deleteById(id) <= 0) {
            throw new ApiException(404, "规则不存在: " + id);
        }
        log.info("disjoint rule deleted: {}", id);
        return loadAll();
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
        int max = 0;
        for (DisjointRule r : listOrdered()) {
            String id = r.id();
            if (id != null && id.length() > 1 && id.startsWith("R")) {
                try { max = Math.max(max, Integer.parseInt(id.substring(1))); }
                catch (NumberFormatException ignore) {}
            }
        }
        return "R" + String.format("%03d", max + 1);
    }

    /** 对单段文本执行冲突检测：逐条规则扫描，返回所有命中 ≥2 候选项的冲突。 */
    public List<DisjointConflictDTO> checkConflicts(String text) {
        List<DisjointConflictDTO> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;
        for (DisjointRule r : listOrdered()) {
            List<String> items = parseItems(r.text());
            if (items.size() < 2) continue;
            List<String> hits = new ArrayList<>();
            for (String it : items) {
                if (text.contains(it)) hits.add(it);
            }
            if (hits.size() >= 2) {
                out.add(new DisjointConflictDTO(r.id(), r.text(), hits));
            }
        }
        return out;
    }

    private List<DisjointRule> listOrdered() {
        return ruleMapper
                .selectList(new LambdaQueryWrapper<RuleDisjoint>().orderByAsc(RuleDisjoint::getId))
                .stream().map(r -> new DisjointRule(r.getId(), r.getText())).toList();
    }
}
