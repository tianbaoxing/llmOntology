package com.conca.ontology.eval.entity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;

/**
 * 评测任务行（eval_run，P6 起替代 data/eval/results/{runId}.json）。
 * 运行态/完成态同表：完成态专有列（finished_at / summary 各列）在运行态为 NULL。
 * startedAt/finishedAt 存 ISO-8601 原文（保纳秒，对齐 skill_analysis 先例）。
 * 老完成文件无 judge/human* 列值：NULL 语义保真，回读重建时对应 key 省略。
 *
 * @author tianbaoxing
 * @date 2026-10-13
 */
@TableName(value = "eval_run", autoResultMap = true)
public class EvalRunEntity {

    @TableId(value = "run_id", type = IdType.INPUT)
    private String runId;

    private String dataset;

    private String datasetHash;

    /** 评测器名有序数组（判分与对比均依赖顺序） */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> graders;

    /** LLM 判分次数（>1 多次取平均；未设置为 NULL） */
    private Integer judgeRuns;

    /** NULL = 老完成文件无 status 字段，读取时按 success 处理 */
    private String status;

    /** 进度描述（running 态） */
    private String detail;

    /** 进度百分比（running 态） */
    private Integer percent;

    /** 失败原因（failed 态） */
    private String error;

    /** 发起人用户 id（start 时留痕） */
    private String userId;

    /** 发起评测的实例标识（P1 多实例：409 文案定位与排查；running_flag 为 DB 生成列，实体不映射） */
    private String ownerInstance;

    /** 进度心跳时间（P1 多实例：writeTaskMeta 落库时刷新，超 15 分钟无心跳判实例失联） */
    private LocalDateTime heartbeatAt;

    /** 开始时间 ISO-8601 原文（保纳秒） */
    private String startedAt;

    /** 完成时间 ISO-8601 原文（running 态 NULL） */
    private String finishedAt;

    private Integer total;

    private Integer passCount;

    private Double passRate;

    private Double avgScore;

    private Integer errorCount;

    private Long avgDurationMs;

    private Long promptTokens;

    private Long completionTokens;

    /** LLM 判分汇总 {model,calls,promptTokens,completionTokens}（嵌套对象整体一列；老 run 无） */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> judge;

    private Integer humanPending;

    private Integer humanScored;

    private Integer humanSkipped;

    /**
     * 从结果文件 Map 填充全部字段（迁移器与服务完成落库共用）。
     * 缺失/类型不符的键按 NULL 处理，三代结果文件 schema 通吃（老/中/新）。
     */
    public static EvalRunEntity of(Map<String, Object> m) {
        EvalRunEntity e = new EvalRunEntity();
        e.runId = str(m.get("id"));
        e.dataset = str(m.get("dataset"));
        e.datasetHash = str(m.get("datasetHash"));
        e.graders = m.get("graders") instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of();
        e.judgeRuns = m.get("judgeRuns") instanceof Number n ? n.intValue() : null;
        e.status = str(m.get("status"));
        e.detail = str(m.get("detail"));
        e.percent = m.get("percent") instanceof Number n ? n.intValue() : null;
        e.error = str(m.get("error"));
        e.userId = str(m.get("userId"));
        e.startedAt = str(m.get("startedAt"));
        e.finishedAt = str(m.get("finishedAt"));
        e.applySummary(m.get("summary") instanceof Map<?, ?> s ? (Map<String, Object>) s : null);
        return e;
    }

    /** summary 拆列写入（12 列，NULL 原样保留；humanScore 重算与迁移共用） */
    public void applySummary(Map<String, Object> s) {
        total = s == null ? null : intVal(s.get("total"));
        passCount = s == null ? null : intVal(s.get("passCount"));
        passRate = s == null ? null : dblVal(s.get("passRate"));
        avgScore = s == null ? null : dblVal(s.get("avgScore"));
        errorCount = s == null ? null : intVal(s.get("errorCount"));
        avgDurationMs = s == null ? null : longVal(s.get("avgDurationMs"));
        promptTokens = s == null ? null : longVal(s.get("promptTokens"));
        completionTokens = s == null ? null : longVal(s.get("completionTokens"));
        judge = s != null && s.get("judge") instanceof Map<?, ?> j ? (Map<String, Object>) j : null;
        humanPending = s == null ? null : intVal(s.get("humanPending"));
        humanScored = s == null ? null : intVal(s.get("humanScored"));
        humanSkipped = s == null ? null : intVal(s.get("humanSkipped"));
    }

    private static String str(Object v) {
        return v instanceof String s ? s : null;
    }

    private static Integer intVal(Object v) {
        return v instanceof Number n ? n.intValue() : null;
    }

    private static Long longVal(Object v) {
        return v instanceof Number n ? n.longValue() : null;
    }

    private static Double dblVal(Object v) {
        return v instanceof Number n ? n.doubleValue() : null;
    }

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public String getDataset() {
        return dataset;
    }

    public void setDataset(String dataset) {
        this.dataset = dataset;
    }

    public String getDatasetHash() {
        return datasetHash;
    }

    public void setDatasetHash(String datasetHash) {
        this.datasetHash = datasetHash;
    }

    public List<String> getGraders() {
        return graders;
    }

    public void setGraders(List<String> graders) {
        this.graders = graders;
    }

    public Integer getJudgeRuns() {
        return judgeRuns;
    }

    public void setJudgeRuns(Integer judgeRuns) {
        this.judgeRuns = judgeRuns;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public Integer getPercent() {
        return percent;
    }

    public void setPercent(Integer percent) {
        this.percent = percent;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getOwnerInstance() {
        return ownerInstance;
    }

    public void setOwnerInstance(String ownerInstance) {
        this.ownerInstance = ownerInstance;
    }

    public LocalDateTime getHeartbeatAt() {
        return heartbeatAt;
    }

    public void setHeartbeatAt(LocalDateTime heartbeatAt) {
        this.heartbeatAt = heartbeatAt;
    }

    public String getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(String startedAt) {
        this.startedAt = startedAt;
    }

    public String getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(String finishedAt) {
        this.finishedAt = finishedAt;
    }

    public Integer getTotal() {
        return total;
    }

    public void setTotal(Integer total) {
        this.total = total;
    }

    public Integer getPassCount() {
        return passCount;
    }

    public void setPassCount(Integer passCount) {
        this.passCount = passCount;
    }

    public Double getPassRate() {
        return passRate;
    }

    public void setPassRate(Double passRate) {
        this.passRate = passRate;
    }

    public Double getAvgScore() {
        return avgScore;
    }

    public void setAvgScore(Double avgScore) {
        this.avgScore = avgScore;
    }

    public Integer getErrorCount() {
        return errorCount;
    }

    public void setErrorCount(Integer errorCount) {
        this.errorCount = errorCount;
    }

    public Long getAvgDurationMs() {
        return avgDurationMs;
    }

    public void setAvgDurationMs(Long avgDurationMs) {
        this.avgDurationMs = avgDurationMs;
    }

    public Long getPromptTokens() {
        return promptTokens;
    }

    public void setPromptTokens(Long promptTokens) {
        this.promptTokens = promptTokens;
    }

    public Long getCompletionTokens() {
        return completionTokens;
    }

    public void setCompletionTokens(Long completionTokens) {
        this.completionTokens = completionTokens;
    }

    public Map<String, Object> getJudge() {
        return judge;
    }

    public void setJudge(Map<String, Object> judge) {
        this.judge = judge;
    }

    public Integer getHumanPending() {
        return humanPending;
    }

    public void setHumanPending(Integer humanPending) {
        this.humanPending = humanPending;
    }

    public Integer getHumanScored() {
        return humanScored;
    }

    public void setHumanScored(Integer humanScored) {
        this.humanScored = humanScored;
    }

    public Integer getHumanSkipped() {
        return humanSkipped;
    }

    public void setHumanSkipped(Integer humanSkipped) {
        this.humanSkipped = humanSkipped;
    }
}
