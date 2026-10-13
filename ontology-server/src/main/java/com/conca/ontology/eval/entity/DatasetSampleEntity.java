package com.conca.ontology.eval.entity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 评测数据集样本行（dataset_sample，P6 起替代 .jsonl 文件内非空行）。
 * 自增 id 兼作文件行序（插入序即原文件非空行序，对齐 eval_run_sample 先例），seq 定位 = 按 id 升序行号。
 * NULL 语义 = 原行无此键（sampleId 读取按 line-{seq}、query 按空串兜底），回读重建 raw 时 NULL 键省略；
 * 全字段 updateStrategy=IGNORED：updateSample 整行覆写（对齐原「重写整行 JSONL」语义，支持字段清空回写）。
 */
@TableName(value = "dataset_sample", autoResultMap = true)
public class DatasetSampleEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField(updateStrategy = FieldStrategy.IGNORED)
    private String datasetName;

    /** 样本 id（如 q001）；NULL = 原行无 id 键，读取路径按 line-{seq} 兜底 */
    @TableField(updateStrategy = FieldStrategy.IGNORED)
    private String sampleId;

    /** 单轮提问（多轮样本 NULL，读取按空串兜底） */
    @TableField(updateStrategy = FieldStrategy.IGNORED)
    private String query;

    /** 预置回答（评测时跳过 agent 直接判分） */
    @TableField(updateStrategy = FieldStrategy.IGNORED)
    private String response;

    /** 参考答案 Object（String / 数字 / 数组原样保真） */
    @TableField(typeHandler = JacksonTypeHandler.class, updateStrategy = FieldStrategy.IGNORED)
    private Object referenceResponse;

    /** 数值容差（number_accuracy 用） */
    @TableField(updateStrategy = FieldStrategy.IGNORED)
    private Double tolerance;

    /** 评分 rubric（读路径支持，源文件暂未使用） */
    @TableField(updateStrategy = FieldStrategy.IGNORED)
    private String rubric;

    /** 多轮脚本 [{query,response?}]（保序，至少 2 轮；单轮样本 NULL） */
    @TableField(typeHandler = JacksonTypeHandler.class, updateStrategy = FieldStrategy.IGNORED)
    private List<Map<String, Object>> turns;

    /** 期望 skills 名称列表（NULL = 原行无此键） */
    @TableField(typeHandler = JacksonTypeHandler.class, updateStrategy = FieldStrategy.IGNORED)
    private List<String> skills;

    /** 期望普通工具名称列表（NULL = 原行无此键） */
    @TableField(typeHandler = JacksonTypeHandler.class, updateStrategy = FieldStrategy.IGNORED)
    private List<String> tools;

    /** 期望 MCP 工具列表（NULL = 原行无此键） */
    @TableField(typeHandler = JacksonTypeHandler.class, updateStrategy = FieldStrategy.IGNORED)
    private List<String> mcps;

    /**
     * 从 JSONL 行节点填充（迁移器专用，键集与原文件行一致）。
     * 解析语义与原 EvalDatasetService.loadSamples 同构：id/query/response/rubric 缺省 NULL、
     * reference_response 保真 Object、turns 严格校验（1 轮 / 空 query 抛异常阻断启动）、
     * 字符串清单空/缺省归 NULL。
     */
    public static DatasetSampleEntity of(String datasetName, JsonNode node, ObjectMapper mapper) {
        DatasetSampleEntity e = new DatasetSampleEntity();
        e.datasetName = datasetName;
        e.sampleId = node.path("id").asText(null);
        e.query = node.path("query").asText(null);
        e.response = node.path("response").asText(null);
        JsonNode ref = node.path("reference_response");
        e.referenceResponse = ref.isMissingNode() || ref.isNull() ? null : mapper.convertValue(ref, Object.class);
        e.tolerance = node.path("tolerance").isNumber() ? node.path("tolerance").asDouble() : null;
        e.rubric = node.path("rubric").asText(null);
        List<Map<String, Object>> turns = parseTurns(node.path("turns"));
        e.turns = turns.isEmpty() ? null : turns;
        e.skills = parseStringList(node.path("skills"));
        e.tools = parseStringList(node.path("tools"));
        e.mcps = parseStringList(node.path("mcps"));
        return e;
    }

    /** 多轮脚本解析（严格校验，语义同原 parseTurns：非数组/空 → 空表，1 轮 / 空 query 抛异常） */
    private static List<Map<String, Object>> parseTurns(JsonNode turnsNode) {
        if (turnsNode == null || !turnsNode.isArray()) {
            return List.of();
        }
        if (turnsNode.size() == 1) {
            throw new IllegalArgumentException("turns 仅 1 轮，请改用单轮 query/response 字段");
        }
        List<Map<String, Object>> turns = new ArrayList<>();
        for (JsonNode t : turnsNode) {
            String q = t.path("query").asText("");
            if (q.isBlank()) {
                throw new IllegalArgumentException("turns 中存在 query 为空的轮次");
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("query", q);
            String r = t.path("response").asText(null);
            if (r != null) {
                m.put("response", r);
            }
            turns.add(m);
        }
        return turns;
    }

    /** 字符串清单解析（语义同原 parseStringList：非数组/缺省 → null，过滤空串，滤后为空 → null） */
    private static List<String> parseStringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return null;
        }
        List<String> list = new ArrayList<>();
        for (JsonNode e : node) {
            if (e.isTextual()) {
                String s = e.asText();
                if (s != null && !s.isBlank()) {
                    list.add(s);
                }
            }
        }
        return list.isEmpty() ? null : list;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getDatasetName() {
        return datasetName;
    }

    public void setDatasetName(String datasetName) {
        this.datasetName = datasetName;
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

    public String getResponse() {
        return response;
    }

    public void setResponse(String response) {
        this.response = response;
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

    public String getRubric() {
        return rubric;
    }

    public void setRubric(String rubric) {
        this.rubric = rubric;
    }

    public List<Map<String, Object>> getTurns() {
        return turns;
    }

    public void setTurns(List<Map<String, Object>> turns) {
        this.turns = turns;
    }

    public List<String> getSkills() {
        return skills;
    }

    public void setSkills(List<String> skills) {
        this.skills = skills;
    }

    public List<String> getTools() {
        return tools;
    }

    public void setTools(List<String> tools) {
        this.tools = tools;
    }

    public List<String> getMcps() {
        return mcps;
    }

    public void setMcps(List<String> mcps) {
        this.mcps = mcps;
    }
}
