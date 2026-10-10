package com.conca.ontology.agent.migration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.conca.ontology.agent.entity.RewriteConfigItem;
import com.conca.ontology.agent.mapper.RewriteConfigItemMapper;
import com.conca.ontology.agent.service.RewriteConfigService;
import com.conca.ontology.shared.db.migration.JsonMigrator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * rewrite-config.json → rewrite_config 多行表存量迁移（P3）。
 * 每项功能一行（semantic/typo/term/context），文件全局 version 不再落库。
 * 全新部署（表空且无 JSON）不插默认行：loadAll 对缺失功能键内存兜底默认值
 * （对齐原「文件不存在返回内置默认」语义）。
 */
@Component
@Order(41)
public class RewriteConfigMigrator implements JsonMigrator {

    private static final Logger log = LoggerFactory.getLogger(RewriteConfigMigrator.class);

    private final ObjectMapper mapper;
    private final RewriteConfigItemMapper itemMapper;
    private final String configFile;

    public RewriteConfigMigrator(ObjectMapper mapper, RewriteConfigItemMapper itemMapper,
            @Value("${app.rewrite.config-file:data/rewrite-config.json}") String configFile) {
        this.mapper = mapper;
        this.itemMapper = itemMapper;
        this.configFile = configFile;
    }

    @Override
    public String name() {
        return "rewrite-config";
    }

    @Override
    public Path sourceFile() {
        return Path.of(configFile).toAbsolutePath().normalize();
    }

    @Override
    public boolean targetTableEmpty() {
        return itemMapper.selectCount(null) == 0;
    }

    @Override
    @Transactional
    public void importAll() throws Exception {
        JsonNode features = mapper.readTree(Files.readString(sourceFile())).path("features");
        LocalDateTime now = LocalDateTime.now();
        for (String type : RewriteConfigService.ALL_TYPES) {
            JsonNode f = features.path(type);
            RewriteConfigItem row = new RewriteConfigItem();
            row.setFeature(type);
            row.setEnabled(f.path("enabled").asBoolean(false));
            row.setPrompt(f.path("prompt").asText());
            row.setUpdatedAt(now);
            itemMapper.insert(row);
        }
        log.info("rewrite-config.json 导入完成：{} 项功能", RewriteConfigService.ALL_TYPES.size());
    }
}
