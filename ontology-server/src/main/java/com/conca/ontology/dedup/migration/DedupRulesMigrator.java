package com.conca.ontology.dedup.migration;

import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.conca.ontology.dedup.entity.RuleDedup;
import com.conca.ontology.dedup.mapper.RuleDedupMapper;
import com.conca.ontology.dedup.model.DedupRuleset;
import com.conca.ontology.dedup.model.DedupRulesetConverter;
import com.conca.ontology.shared.db.migration.JsonMigrator;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * dedup-rules.json → rule_dedup 存量迁移（P2）。
 * 全新部署（表空且无 JSON）无默认数据：无行 = 尚未创建规则（对齐原单文件「文件不存在 = 未创建」语义）。
 */
@Component
@Order(32)
public class DedupRulesMigrator implements JsonMigrator {

    private static final Logger log = LoggerFactory.getLogger(DedupRulesMigrator.class);

    private final ObjectMapper mapper;
    private final RuleDedupMapper ruleMapper;
    private final String rulesFile;

    public DedupRulesMigrator(ObjectMapper mapper, RuleDedupMapper ruleMapper,
            @Value("${graph.dedup.rules-file:data/dedup-rules.json}") String rulesFile) {
        this.mapper = mapper;
        this.ruleMapper = ruleMapper;
        this.rulesFile = rulesFile;
    }

    @Override
    public String name() {
        return "dedup-rules";
    }

    @Override
    public Path sourceFile() {
        return Path.of(rulesFile).toAbsolutePath().normalize();
    }

    @Override
    public boolean targetTableEmpty() {
        return ruleMapper.selectCount(null) == 0;
    }

    @Override
    @Transactional
    public void importAll() throws Exception {
        DedupRuleset r = mapper.readValue(Files.readString(sourceFile()), DedupRuleset.class);
        ruleMapper.insert(DedupRulesetConverter.toEntity(r));
        log.info("dedup-rules.json 导入完成：id={} name={} version={}", r.id(), r.name(), r.version());
    }
}
