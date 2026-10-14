package com.conca.ontology.skill;

import java.util.List;

import com.conca.ontology.skill.entity.SkillAnalysisEntity;

/**
 * {@link SkillAnalysis} record ↔ {@link SkillAnalysisEntity} 转换器（P4）。
 * SkillAnalysisStore 持久化用。
 * toEntity 维持记录不变式：六个 NOT NULL 清单列 null 归一化为空数组（列 NOT NULL），
 * hidden* 三列原样透传（列 NULLable，旧数据缺字段保真存 NULL）。
 */
public final class SkillAnalysisConverter {

    private SkillAnalysisConverter() {
    }

    public static SkillAnalysis toModel(SkillAnalysisEntity e) {
        return new SkillAnalysis(
                e.getAnalyzedAt(),
                e.getContentHash(),
                e.getScannedFiles(),
                new SkillAnalysis.ToolUsage(e.getToolsUsed(), e.getToolsNotUsed()),
                e.getApis(),
                e.getShellCommands(),
                e.getKeyInfo(),
                e.getHiddenTools(),
                e.getHiddenApis(),
                e.getHiddenShellCommands());
    }

    public static SkillAnalysisEntity toEntity(String skillName, SkillAnalysis a) {
        SkillAnalysisEntity e = new SkillAnalysisEntity();
        e.setSkillName(skillName);
        e.setAnalyzedAt(a.analyzedAt());
        e.setContentHash(a.contentHash());
        e.setScannedFiles(nz(a.scannedFiles()));
        SkillAnalysis.ToolUsage tools = a.tools();
        e.setToolsUsed(tools == null ? List.of() : nz(tools.used()));
        e.setToolsNotUsed(tools == null ? List.of() : nz(tools.notUsed()));
        e.setApis(nz(a.apis()));
        e.setShellCommands(nz(a.shellCommands()));
        e.setKeyInfo(nz(a.keyInfo()));
        e.setHiddenTools(a.hiddenTools());
        e.setHiddenApis(a.hiddenApis());
        e.setHiddenShellCommands(a.hiddenShellCommands());
        return e;
    }

    private static <T> List<T> nz(List<T> l) {
        return l == null ? List.of() : l;
    }
}
