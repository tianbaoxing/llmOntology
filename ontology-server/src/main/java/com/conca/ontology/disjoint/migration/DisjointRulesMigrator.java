package com.conca.ontology.disjoint.migration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.conca.ontology.disjoint.entity.RuleDisjoint;
import com.conca.ontology.disjoint.mapper.RuleDisjointMapper;
import com.conca.ontology.shared.db.migration.JsonMigrator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * disjoint-rules.json → rule_disjoint 多行表存量迁移（P2）。
 * 每条规则一行，文件里的全局 version 字段不再落库（多行存储下无意义）。
 * 全新部署（表空且无 JSON）无默认数据：无行 = 空词典（对齐原「文件不存在」语义）。
 */
@Component
@Order(33)
public class DisjointRulesMigrator implements JsonMigrator {

    private static final Logger log = LoggerFactory.getLogger(DisjointRulesMigrator.class);

    private final ObjectMapper mapper;
    private final RuleDisjointMapper ruleMapper;
    private final String disjointRulesFile;

    public DisjointRulesMigrator(ObjectMapper mapper, RuleDisjointMapper ruleMapper,
            @Value("${graph.disjoint-rules-file:data/disjoint-rules.json}") String disjointRulesFile) {
        this.mapper = mapper;
        this.ruleMapper = ruleMapper;
        this.disjointRulesFile = disjointRulesFile;
    }

    @Override
    public String name() {
        return "disjoint-rules";
    }

    @Override
    public Path sourceFile() {
        return Path.of(disjointRulesFile).toAbsolutePath().normalize();
    }

    @Override
    public boolean targetTableEmpty() {
        return ruleMapper.selectCount(null) == 0;
    }

    @Override
    @Transactional
    public void importAll() throws Exception {
        JsonNode rules = mapper.readTree(Files.readString(sourceFile())).path("rules");
        int count = 0;
        for (JsonNode r : rules) {
            RuleDisjoint row = new RuleDisjoint();
            row.setId(r.path("id").asText());
            row.setText(r.path("text").asText());
            row.setUpdateTime(LocalDateTime.now());
            ruleMapper.insert(row);
            count++;
        }
        log.info("disjoint-rules.json 导入完成：{} 条规则", count);
    }
}
