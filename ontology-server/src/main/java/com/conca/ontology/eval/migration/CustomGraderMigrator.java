package com.conca.ontology.eval.migration;

import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.conca.ontology.eval.entity.CustomGraderEntity;
import com.conca.ontology.eval.mapper.CustomGraderMapper;
import com.conca.ontology.eval.model.CustomGraderDefinition;
import com.conca.ontology.shared.db.migration.JsonMigrator;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * custom-graders.json → custom_grader 多行表存量迁移（P6 评测一）。
 * 每评测器一行（name 主键），扁平字段逐列拆出；时间字段 ISO 原文保纳秒。
 * 全新部署无默认数据：表空 = 无自定义评测器，对齐原「文件不存在」语义（initDefaults 空实现）。
 */
@Component
@Order(70)
public class CustomGraderMigrator implements JsonMigrator {

    private static final Logger log = LoggerFactory.getLogger(CustomGraderMigrator.class);

    private final ObjectMapper mapper;
    private final CustomGraderMapper graderMapper;
    private final String gradersFile;

    public CustomGraderMigrator(ObjectMapper mapper, CustomGraderMapper graderMapper,
            @Value("${app.eval.custom-graders-file:data/eval/custom-graders.json}") String gradersFile) {
        this.mapper = mapper;
        this.graderMapper = graderMapper;
        this.gradersFile = gradersFile;
    }

    @Override
    public String name() {
        return "custom-graders";
    }

    @Override
    public Path sourceFile() {
        return Path.of(gradersFile).toAbsolutePath().normalize();
    }

    @Override
    public boolean targetTableEmpty() {
        return graderMapper.selectCount(null) == 0;
    }

    @Override
    @Transactional
    public void importAll() throws Exception {
        CustomGraderDefinition[] defs = mapper.readValue(sourceFile().toFile(), CustomGraderDefinition[].class);
        int count = 0;
        for (CustomGraderDefinition d : defs) {
            graderMapper.insert(CustomGraderEntity.of(d));
            count++;
        }
        log.info("custom-graders.json 导入完成：{} 个自定义评测器", count);
    }
}
