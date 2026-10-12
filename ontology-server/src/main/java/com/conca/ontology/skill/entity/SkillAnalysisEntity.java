package com.conca.ontology.skill.entity;

import java.util.List;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.conca.ontology.skill.SkillAnalysis;

/**
 * 技能使用分析行（skill_analysis，P4：每技能一行，无全局 version）。
 * 与 {@link SkillAnalysis} record 经 {@link com.conca.ontology.skill.SkillAnalysisConverter} 互转。
 * analyzedAt / contentHash 可空：keyInfo-only 记录（仅保存识别关键词、尚未检测）二者为 NULL。
 * 六个清单列为类型化 JSON（保序）；hidden* 三列可空，旧数据缺字段时存 NULL 保真。
 */
@TableName(value = "skill_analysis", autoResultMap = true)
public class SkillAnalysisEntity {

    @TableId(value = "skill_name", type = IdType.INPUT)
    private String skillName;

    /** 分析时间 ISO-8601 原文（保纳秒精度） */
    private String analyzedAt;

    /** 扫描文件内容哈希 SHA-256（详情页对比判过期） */
    private String contentHash;

    /** 参与扫描的文件相对路径（保序） */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> scannedFiles;

    /** 被使用的系统工具（按名排序） */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<SkillAnalysis.ToolHit> toolsUsed;

    /** 未使用的系统工具名（按名排序） */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> toolsNotUsed;

    /** 提取到的 API 路径（去重保序） */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> apis;

    /** 提取到的 shell 命令（按命令排序） */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<SkillAnalysis.ShellCmd> shellCommands;

    /** 用户填写的识别关键词（特征指纹，独立于检测结果保留） */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> keyInfo;

    /** 用户删除（忽略）的工具检测项（可空） */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> hiddenTools;

    /** 用户删除（忽略）的 API 检测项（可空） */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> hiddenApis;

    /** 用户删除（忽略）的 shell 命令检测项（可空） */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> hiddenShellCommands;

    public String getSkillName() {
        return skillName;
    }

    public void setSkillName(String skillName) {
        this.skillName = skillName;
    }

    public String getAnalyzedAt() {
        return analyzedAt;
    }

    public void setAnalyzedAt(String analyzedAt) {
        this.analyzedAt = analyzedAt;
    }

    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }

    public List<String> getScannedFiles() {
        return scannedFiles;
    }

    public void setScannedFiles(List<String> scannedFiles) {
        this.scannedFiles = scannedFiles;
    }

    public List<SkillAnalysis.ToolHit> getToolsUsed() {
        return toolsUsed;
    }

    public void setToolsUsed(List<SkillAnalysis.ToolHit> toolsUsed) {
        this.toolsUsed = toolsUsed;
    }

    public List<String> getToolsNotUsed() {
        return toolsNotUsed;
    }

    public void setToolsNotUsed(List<String> toolsNotUsed) {
        this.toolsNotUsed = toolsNotUsed;
    }

    public List<String> getApis() {
        return apis;
    }

    public void setApis(List<String> apis) {
        this.apis = apis;
    }

    public List<SkillAnalysis.ShellCmd> getShellCommands() {
        return shellCommands;
    }

    public void setShellCommands(List<SkillAnalysis.ShellCmd> shellCommands) {
        this.shellCommands = shellCommands;
    }

    public List<String> getKeyInfo() {
        return keyInfo;
    }

    public void setKeyInfo(List<String> keyInfo) {
        this.keyInfo = keyInfo;
    }

    public List<String> getHiddenTools() {
        return hiddenTools;
    }

    public void setHiddenTools(List<String> hiddenTools) {
        this.hiddenTools = hiddenTools;
    }

    public List<String> getHiddenApis() {
        return hiddenApis;
    }

    public void setHiddenApis(List<String> hiddenApis) {
        this.hiddenApis = hiddenApis;
    }

    public List<String> getHiddenShellCommands() {
        return hiddenShellCommands;
    }

    public void setHiddenShellCommands(List<String> hiddenShellCommands) {
        this.hiddenShellCommands = hiddenShellCommands;
    }
}
