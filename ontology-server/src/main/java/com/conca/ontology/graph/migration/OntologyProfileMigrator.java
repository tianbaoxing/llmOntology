package com.conca.ontology.graph.migration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.conca.ontology.graph.entity.OntologyProfileItem;
import com.conca.ontology.graph.mapper.OntologyProfileItemMapper;
import com.conca.ontology.graph.model.ProfileEntry;
import com.conca.ontology.graph.model.ProfileFile;
import com.conca.ontology.shared.db.migration.JsonMigrator;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * ontology-profile.json → ontology_profile 多行表存量迁移（P2）。
 * 每条问答一行，文件里的全局 version 字段不再落库（多行存储下无意义）。
 * 全新部署（表空且无 JSON）无默认数据：无行 = 尚未创建（对齐原「文件不存在返回空列表」语义）。
 */
@Component
@Order(31)
public class OntologyProfileMigrator implements JsonMigrator {

    private static final Logger log = LoggerFactory.getLogger(OntologyProfileMigrator.class);

    private final ObjectMapper mapper;
    private final OntologyProfileItemMapper itemMapper;
    private final String profileFile;

    public OntologyProfileMigrator(ObjectMapper mapper, OntologyProfileItemMapper itemMapper,
            @Value("${graph.profile-file:data/ontology-profile.json}") String profileFile) {
        this.mapper = mapper;
        this.itemMapper = itemMapper;
        this.profileFile = profileFile;
    }

    @Override
    public String name() {
        return "ontology-profile";
    }

    @Override
    public Path sourceFile() {
        return Path.of(profileFile).toAbsolutePath().normalize();
    }

    @Override
    public boolean targetTableEmpty() {
        return itemMapper.selectCount(null) == 0;
    }

    @Override
    @Transactional
    public void importAll() throws Exception {
        ProfileFile pf = mapper.readValue(Files.readString(sourceFile()), ProfileFile.class);
        List<ProfileEntry> items = pf.items() == null ? List.of() : pf.items();
        for (ProfileEntry e : items) {
            OntologyProfileItem row = new OntologyProfileItem();
            row.setPhase(e.phase());
            row.setItemKey(e.key());
            row.setQuestion(e.question());
            row.setAnswer(e.answer());
            row.setUpdatedAt(e.updatedAt());
            itemMapper.insert(row);
        }
        log.info("ontology-profile.json 导入完成：{} 条问询记录", items.size());
    }
}
