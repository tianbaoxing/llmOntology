package com.conca.ontology.skill.migration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.conca.ontology.shared.db.migration.JsonMigrator;
import com.conca.ontology.skill.SkillAnalysis;
import com.conca.ontology.skill.SkillAnalysisConverter;
import com.conca.ontology.skill.entity.SkillAnalysisEntity;
import com.conca.ontology.skill.mapper.SkillAnalysisMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * skill-analysis.json → skill_analysis 多行表存量迁移（P4）。
 * 每技能一行（skills 按技能名索引），顶层 version 不再落库（仅在旧文件写盘时回填、从未被读取）。
 * 全新部署无默认数据：表空 = 尚无分析结果，对齐原「文件不存在」语义（initDefaults 空实现）。
 */
@Component
@Order(51)
public class SkillAnalysisMigrator implements JsonMigrator {

    private static final Logger log = LoggerFactory.getLogger(SkillAnalysisMigrator.class);

    private final ObjectMapper mapper;
    private final SkillAnalysisMapper skillMapper;
    private final String analysisFile;

    public SkillAnalysisMigrator(ObjectMapper mapper, SkillAnalysisMapper skillMapper,
            @Value("${app.skill-analysis-file:data/skill-analysis.json}") String analysisFile) {
        this.mapper = mapper;
        this.skillMapper = skillMapper;
        this.analysisFile = analysisFile;
    }

    @Override
    public String name() {
        return "skill-analysis";
    }

    @Override
    public Path sourceFile() {
        return Path.of(analysisFile).toAbsolutePath().normalize();
    }

    @Override
    public boolean targetTableEmpty() {
        return skillMapper.selectCount(null) == 0;
    }

    @Override
    @Transactional
    public void importAll() throws Exception {
        JsonNode skills = mapper.readTree(Files.readString(sourceFile())).path("skills");
        int count = 0;
        for (Iterator<Map.Entry<String, JsonNode>> it = skills.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> en = it.next();
            SkillAnalysis sa = mapper.treeToValue(en.getValue(), SkillAnalysis.class);
            SkillAnalysisEntity row = SkillAnalysisConverter.toEntity(en.getKey(), sa);
            skillMapper.insert(row);
            count++;
        }
        log.info("skill-analysis.json 导入完成：{} 个技能", count);
    }
}
