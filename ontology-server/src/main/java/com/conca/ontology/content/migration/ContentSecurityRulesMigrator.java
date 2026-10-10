package com.conca.ontology.content.migration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.conca.ontology.content.entity.RuleContentSecurity;
import com.conca.ontology.content.mapper.RuleContentSecurityMapper;
import com.conca.ontology.content.service.ContentSecurityService;
import com.conca.ontology.shared.db.migration.JsonMigrator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * content-security-rules.json → rule_content_security 多行表存量迁移（P3）。
 * 每条规则一行，文件全局 version 不再落库；allowlisted 落行级标记列。
 * 导入后触发 ContentSecurityService 缓存重载（正则预编译 + allowlist 集合）。
 * 全新部署无默认规则：无行 = 无规则（对齐原「文件不存在」语义）。
 */
@Component
@Order(40)
public class ContentSecurityRulesMigrator implements JsonMigrator {

    private static final Logger log = LoggerFactory.getLogger(ContentSecurityRulesMigrator.class);

    private final ObjectMapper mapper;
    private final RuleContentSecurityMapper ruleMapper;
    private final ContentSecurityService contentSecurityService;
    private final String rulesFile;

    public ContentSecurityRulesMigrator(ObjectMapper mapper, RuleContentSecurityMapper ruleMapper,
            ContentSecurityService contentSecurityService,
            @Value("${app.content-security.rules-file:data/content-security-rules.json}") String rulesFile) {
        this.mapper = mapper;
        this.ruleMapper = ruleMapper;
        this.contentSecurityService = contentSecurityService;
        this.rulesFile = rulesFile;
    }

    @Override
    public String name() {
        return "content-security-rules";
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
        JsonNode rules = mapper.readTree(Files.readString(sourceFile())).path("rules");
        LocalDateTime now = LocalDateTime.now();
        int ord = 0;
        for (JsonNode r : rules) {
            RuleContentSecurity row = new RuleContentSecurity();
            row.setId(r.path("id").asText());
            row.setCategory(r.path("category").asText());
            row.setSeverity(r.path("severity").asText());
            row.setAction(r.path("action").asText());
            row.setDescription(r.path("description").asText());
            row.setPattern(r.path("pattern").asText());
            row.setAllowlisted(r.path("allowlisted").asBoolean(false));
            row.setOrd(++ord);
            row.setUpdatedAt(now);
            ruleMapper.insert(row);
        }
        contentSecurityService.reloadAll();
        log.info("content-security-rules.json 导入完成：{} 条规则", ord);
    }
}
