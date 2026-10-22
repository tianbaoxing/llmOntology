package com.conca.ontology.skill;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.conca.ontology.skill.converter.SkillAnalysisConverter;
import com.conca.ontology.skill.dto.SkillAnalysis;
import com.conca.ontology.skill.entity.SkillAnalysisEntity;
import com.conca.ontology.skill.mapper.SkillAnalysisMapper;

/**
 * 技能分析结果持久层（MySQL skill_analysis，P4 起替代 data/skill-analysis.json 单文件）：
 * 每技能一行，行级 CRUD（get 单行查询 / put 行级 upsert / all 全表按技能名序）。
 * 记录级语义不变：keyInfo-only 记录（仅保存识别关键词、尚未检测）analyzedAt/contentHash 为 NULL；
 * hidden* 忽略清单 NULL 保真（旧数据缺字段）。put 保留 synchronized（读-改-写串行，对齐原实现并发语义）。
 *
 * @author tianbaoxing
 * @date 2026-09-16
 */
@Component
public class SkillAnalysisStore {

    private final SkillAnalysisMapper skillMapper;

    public SkillAnalysisStore(SkillAnalysisMapper skillMapper) {
        this.skillMapper = skillMapper;
    }

    public SkillAnalysis get(String name) {
        SkillAnalysisEntity e = skillMapper.selectById(name);
        return e == null ? null : SkillAnalysisConverter.toModel(e);
    }

    /** 全部分析结果（技能名 → 分析记录，按主键技能名排序） */
    public Map<String, SkillAnalysis> all() {
        Map<String, SkillAnalysis> out = new LinkedHashMap<>();
        List<SkillAnalysisEntity> rows = skillMapper.selectList(Wrappers.<SkillAnalysisEntity>lambdaQuery()
                .orderByAsc(SkillAnalysisEntity::getSkillName));
        for (SkillAnalysisEntity e : rows) {
            out.put(e.getSkillName(), SkillAnalysisConverter.toModel(e));
        }
        return out;
    }

    public synchronized void put(String name, SkillAnalysis analysis) {
        SkillAnalysisEntity row = SkillAnalysisConverter.toEntity(name, analysis);
        if (skillMapper.selectById(name) == null) {
            skillMapper.insert(row);
        } else {
            skillMapper.updateById(row);
        }
    }
}
