package com.conca.ontology.eval.entity;

import java.util.List;
import java.util.Map;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;

/**
 * 评测任务样本明细行（eval_run_sample，P6 起替代结果文件内 samples 数组）。
 * 自增 id 兼作 samples 数组顺序（插入序即原文件序，对齐 file_metadata 先例）。
 * referenceResponse 为 Object（数据集侧即 String 或嵌套 JSON 值，原样保真）；
 * toolCalls/scores NOT NULL 归一空数组（[]）；dialogue 仅多轮样本有。
 *
 * @author tianbaoxing
 * @date 2026-10-13
 */
@TableName(value = "eval_run_sample", autoResultMap = true)
public class EvalRunSampleEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String runId;

    /** 样本 id（数据集内唯一，如 q001） */
    private String sampleId;

    /** 提问（多轮为「轮1：… ◆ 轮2：…」拼接展示文本） */
    private String query;

    /** 参考回答（新格式；老格式 expected 死字段不迁移） */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Object referenceResponse;

    private Double tolerance;

    /** 多轮轮数（单轮样本无此键） */
    private Integer turnCount;

    /** 回答来源：preset | agent */
    private String responseSource;

    /** agent 最终回答（失败样本为空串） */
    private String answer;

    /** 工具调用轨迹 [{name,args,result,state}]（保序） */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<Map<String, Object>> toolCalls;

    /** 多轮对话记录 [{query,answer}]（仅多轮样本有） */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<Map<String, Object>> dialogue;

    private Integer rounds;

    private Long durationMs;

    private Long promptTokens;

    private Long completionTokens;

    private String agentError;

    /** 各评测器得分 [{name,score,reason,error,metadata,pending}]（保序；人工评分就地改写） */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<Map<String, Object>> scores;

    /**
     * 从结果样本行 Map 填充（迁移器与服务完成落盘共用，键集与结果文件 samples[] 一致）。
     * 老格式 expected 死字段直接丢弃；缺失键按 NULL / 空数组处理。
     */
    @SuppressWarnings("unchecked")
    public static EvalRunSampleEntity of(String runId, Map<String, Object> r) {
        EvalRunSampleEntity e = new EvalRunSampleEntity();
        e.runId = runId;
        e.sampleId = String.valueOf(r.get("id"));
        e.query = r.get("query") instanceof String s ? s : "";
        e.referenceResponse = r.get("referenceResponse");
        e.tolerance = r.get("tolerance") instanceof Number n ? n.doubleValue() : null;
        e.turnCount = r.get("turns") instanceof Number n ? n.intValue() : null;
        e.responseSource = r.get("responseSource") instanceof String s ? s : null;
        e.answer = r.get("answer") instanceof String s ? s : null;
        e.toolCalls = r.get("toolCalls") instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
        e.dialogue = r.get("dialogue") instanceof List<?> l ? (List<Map<String, Object>>) l : null;
        e.rounds = r.get("rounds") instanceof Number n ? n.intValue() : 0;
        e.durationMs = r.get("durationMs") instanceof Number n ? n.longValue() : 0L;
        e.promptTokens = r.get("promptTokens") instanceof Number n ? n.longValue() : 0L;
        e.completionTokens = r.get("completionTokens") instanceof Number n ? n.longValue() : 0L;
        e.agentError = r.get("agentError") instanceof String s ? s : null;
        e.scores = r.get("scores") instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
        return e;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public String getSampleId() {
        return sampleId;
    }

    public void setSampleId(String sampleId) {
        this.sampleId = sampleId;
    }

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }

    public Object getReferenceResponse() {
        return referenceResponse;
    }

    public void setReferenceResponse(Object referenceResponse) {
        this.referenceResponse = referenceResponse;
    }

    public Double getTolerance() {
        return tolerance;
    }

    public void setTolerance(Double tolerance) {
        this.tolerance = tolerance;
    }

    public Integer getTurnCount() {
        return turnCount;
    }

    public void setTurnCount(Integer turnCount) {
        this.turnCount = turnCount;
    }

    public String getResponseSource() {
        return responseSource;
    }

    public void setResponseSource(String responseSource) {
        this.responseSource = responseSource;
    }

    public String getAnswer() {
        return answer;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }

    public List<Map<String, Object>> getToolCalls() {
        return toolCalls;
    }

    public void setToolCalls(List<Map<String, Object>> toolCalls) {
        this.toolCalls = toolCalls;
    }

    public List<Map<String, Object>> getDialogue() {
        return dialogue;
    }

    public void setDialogue(List<Map<String, Object>> dialogue) {
        this.dialogue = dialogue;
    }

    public Integer getRounds() {
        return rounds;
    }

    public void setRounds(Integer rounds) {
        this.rounds = rounds;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Long durationMs) {
        this.durationMs = durationMs;
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

    public String getAgentError() {
        return agentError;
    }

    public void setAgentError(String agentError) {
        this.agentError = agentError;
    }

    public List<Map<String, Object>> getScores() {
        return scores;
    }

    public void setScores(List<Map<String, Object>> scores) {
        this.scores = scores;
    }
}
