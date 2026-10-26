package com.conca.ontology.eval.service;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.conca.ontology.agent.service.AgentSessionService;
import com.conca.ontology.eval.dto.EvalSampleDTO;
import com.conca.ontology.eval.dto.FromChatSampleResultDTO;
import com.conca.ontology.eval.entity.DatasetEntity;
import com.conca.ontology.eval.entity.DatasetSampleEntity;
import com.conca.ontology.eval.mapper.DatasetMapper;
import com.conca.ontology.eval.mapper.DatasetSampleMapper;
import com.conca.ontology.eval.model.EvalSample;
import com.conca.ontology.shared.web.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 评测数据集服务（P6 起存储层 DB 化：dataset + dataset_sample 两表，原为 data/eval/datasets/*.jsonl 整文件读写）。
 * 行序语义保真：自增 id 兼作原文件行序，seq = 按 id 升序行号，中删后自然前移 = 原文件删行语义；
 * NULL 键保真：原行无此键 → 列 NULL，回读 raw/视图按原键序重建、NULL 键省略。
 * 公共方法签名、校验、文案、响应结构均与文件版一致（Controller/前端零改动）。
 */

/**
 * @author tianbaoxing
 * @date 2026-09-18
 */
@Service
public class EvalDatasetService {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final Pattern DATASET_NAME_RE = Pattern.compile("^[A-Za-z0-9._-]+\\.jsonl$");

    private final ObjectMapper mapper;
    private final AgentSessionService agentSessionService;
    private final DatasetMapper datasetMapper;
    private final DatasetSampleMapper sampleMapper;

    public EvalDatasetService(ObjectMapper mapper,
            AgentSessionService agentSessionService, DatasetMapper datasetMapper,
            DatasetSampleMapper sampleMapper) {
        this.mapper = mapper;
        this.agentSessionService = agentSessionService;
        this.datasetMapper = datasetMapper;
        this.sampleMapper = sampleMapper;
    }

    public Map<String, Object> listDatasets() {
        List<Map<String, Object>> list = new ArrayList<>();
        try {
            for (DatasetEntity d : datasetMapper.selectList(
                    Wrappers.<DatasetEntity>lambdaQuery().orderByAsc(DatasetEntity::getName))) {
                Map<String, Object> ds = new LinkedHashMap<>();
                ds.put("name", d.getName());
                ds.put("count", sampleMapper.selectCount(Wrappers.<DatasetSampleEntity>lambdaQuery()
                        .eq(DatasetSampleEntity::getDatasetName, d.getName())));
                list.add(ds);
            }
        } catch (RuntimeException e) {
            throw new ApiException(500, "扫描数据集目录失败: " + e.getMessage());
        }
        return Map.of("datasets", list);
    }

    public Map<String, Object> datasetPreview(String name, int limit) {
        List<Map<String, Object>> all = readAllSamples(name);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("samples", all.subList(0, Math.min(all.size(), Math.max(1, limit))));
        return m;
    }

    public Map<String, Object> datasetSamples(String name, int page, int pageSize) {
        List<Map<String, Object>> all = readAllSamples(name);
        int size = Math.min(Math.max(pageSize, 1), 100);
        int total = all.size();
        int pages = Math.max((total + size - 1) / size, 1);
        int cur = Math.min(Math.max(page, 1), pages);
        List<Map<String, Object>> slice = all.subList(Math.min((cur - 1) * size, total), Math.min(cur * size, total));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("page", cur);
        m.put("pageSize", size);
        m.put("total", total);
        m.put("pages", pages);
        m.put("samples", new ArrayList<>(slice));
        return m;
    }

    @SuppressWarnings("unchecked")
    public FromChatSampleResultDTO sampleFromChat(String userId, String sessionId, int msgIndex) {
        Map<String, Object> data = agentSessionService.messages(userId, sessionId);
        List<Map<String, Object>> msgs = (List<Map<String, Object>>) data.get("messages");
        if (msgs == null || msgIndex < 0 || msgIndex >= msgs.size()) {
            throw new ApiException(400, "msgIndex 越界（会话共 " + (msgs == null ? 0 : msgs.size()) + " 条消息）");
        }
        Map<String, Object> target = msgs.get(msgIndex);
        if (!"assistant".equals(target.get("role"))) {
            throw new ApiException(400, "msgIndex 必须指向 AI 回答消息");
        }
        String answer = target.get("answer") == null ? "" : String.valueOf(target.get("answer"));
        if (answer.isBlank()) {
            throw new ApiException(400, "该消息无回答内容，无法沉淀");
        }
        String query = null;
        int turnIndex = 0;
        for (int i = 0; i < msgIndex; i++) {
            Map<String, Object> m = msgs.get(i);
            if ("user".equals(m.get("role"))) {
                turnIndex++;
                query = m.get("content") == null ? "" : String.valueOf(m.get("content"));
            }
        }
        if (query == null || query.isBlank()) {
            throw new ApiException(400, "未找到对应的提问");
        }
        String ts = target.get("ts") == null ? "" : String.valueOf(target.get("ts"));
        return new FromChatSampleResultDTO(sessionId, msgIndex, query, answer, ts, turnIndex, msgs.size());
    }

    public synchronized Map<String, Object> addSample(String name, EvalSampleDTO req) {
        requireDataset(name);
        DatasetSampleEntity e = buildSampleEntity(name, req, null);
        try {
            sampleMapper.insert(e);
        } catch (RuntimeException ex) {
            throw new ApiException(500, "写入数据集失败: " + ex.getMessage());
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("total", countOf(name));
        return m;
    }

    public synchronized Map<String, Object> updateSample(String name, int seq, EvalSampleDTO req) {
        requireDataset(name);
        DatasetSampleEntity target = rowBySeq(rowsOf(name), seq);
        DatasetSampleEntity e = buildSampleEntity(name, req, target.getId());
        e.setId(target.getId());
        try {
            sampleMapper.updateById(e);
        } catch (RuntimeException ex) {
            throw new ApiException(500, "写入数据集失败: " + ex.getMessage());
        }
        return Map.of("name", name, "seq", seq);
    }

    public synchronized Map<String, Object> deleteSample(String name, int seq) {
        requireDataset(name);
        DatasetSampleEntity target = rowBySeq(rowsOf(name), seq);
        try {
            sampleMapper.deleteById(target.getId());
        } catch (RuntimeException ex) {
            throw new ApiException(500, "写入数据集失败: " + ex.getMessage());
        }
        return Map.of("name", name, "seq", seq, "total", countOf(name));
    }

    public synchronized Map<String, Object> createDataset(String name) {
        if (name == null || !DATASET_NAME_RE.matcher(name).matches()) {
            throw new ApiException(400, "非法数据集文件名: " + name + "（须形如 my-ds.jsonl）");
        }
        if (datasetMapper.selectById(name) != null) {
            throw new ApiException(409, "数据集已存在: " + name);
        }
        try {
            datasetMapper.insert(new DatasetEntity(name));
        } catch (RuntimeException e) {
            throw new ApiException(500, "创建数据集失败: " + e.getMessage());
        }
        return Map.of("name", name, "count", 0);
    }

    public List<EvalSample> loadSamples(String dataset) throws IOException {
        requireDataset(dataset);
        List<EvalSample> list = new ArrayList<>();
        int seq = 0;
        for (DatasetSampleEntity e : rowsOf(dataset)) {
            seq++;
            list.add(new EvalSample(
                    e.getSampleId() != null ? e.getSampleId() : "line-" + seq,
                    e.getQuery() == null ? "" : e.getQuery(),
                    e.getResponse(),
                    e.getReferenceResponse(),
                    e.getTolerance(),
                    e.getRubric(),
                    e.getTurns() == null ? List.of() : toTurns(e.getTurns()),
                    e.getSkills(),
                    e.getTools(),
                    e.getMcps()));
        }
        return list;
    }

    /** 校验数据集名并确认存在（原 requireDataset 文案不变；404 = 表无该行） */
    public void requireDataset(String name) {
        if (name == null || !DATASET_NAME_RE.matcher(name).matches()) {
            throw new ApiException(400, "非法数据集文件名: " + name);
        }
        if (datasetMapper.selectById(name) == null) {
            throw new ApiException(404, "数据集不存在: " + name);
        }
    }

    /**
     * 全库搜索引用了指定名称（带双引号精确匹配）的样本行，返回「数据集名 第N行」引用清单
     * （自定义评测器删除前置校验用，替代原目录逐文件 contains 扫描，输出格式不变）。
     */
    public List<String> searchGraderReferences(String graderName) {
        String needle = "\"" + graderName + "\"";
        List<String> refs = new ArrayList<>();
        for (DatasetEntity d : datasetMapper.selectList(
                Wrappers.<DatasetEntity>lambdaQuery().orderByAsc(DatasetEntity::getName))) {
            int seq = 0;
            for (DatasetSampleEntity e : rowsOf(d.getName())) {
                seq++;
                if (buildRaw(e).contains(needle)) {
                    refs.add(d.getName() + " 第" + seq + "行");
                }
            }
        }
        return refs;
    }

    // ---------------- 内部：行读取与视图重建 ----------------

    private List<DatasetSampleEntity> rowsOf(String name) {
        return sampleMapper.selectList(Wrappers.<DatasetSampleEntity>lambdaQuery()
                .eq(DatasetSampleEntity::getDatasetName, name)
                .orderByAsc(DatasetSampleEntity::getId));
    }

    private long countOf(String name) {
        return sampleMapper.selectCount(Wrappers.<DatasetSampleEntity>lambdaQuery()
                .eq(DatasetSampleEntity::getDatasetName, name));
    }

    /** 按 seq（id 升序行号）定位行，越界文案与原 indexOfSeq 未命中一致 */
    private static DatasetSampleEntity rowBySeq(List<DatasetSampleEntity> rows, int seq) {
        if (seq < 1 || seq > rows.size()) {
            throw new ApiException(404, "样本不存在: 第 " + seq + " 条（数据集可能已被修改，请刷新）");
        }
        return rows.get(seq - 1);
    }

    private List<Map<String, Object>> readAllSamples(String name) {
        requireDataset(name);
        try {
            List<Map<String, Object>> samples = new ArrayList<>();
            int seq = 0;
            for (DatasetSampleEntity e : rowsOf(name)) {
                samples.add(viewRow(e, ++seq));
            }
            return samples;
        } catch (RuntimeException e) {
            throw new ApiException(500, "读取数据集失败: " + e.getMessage());
        }
    }

    /** 实体 → 样本视图行（键集与键序同原 parseSampleRow；DB 行恒为合法结构，valid 恒 true） */
    private Map<String, Object> viewRow(DatasetSampleEntity e, int seq) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("seq", seq);
        row.put("raw", buildRaw(e));
        row.put("id", e.getSampleId() != null ? e.getSampleId() : "line-" + seq);
        if (e.getTurns() != null) {
            StringBuilder q = new StringBuilder("【多轮 " + e.getTurns().size() + " 轮】");
            for (int i = 0; i < e.getTurns().size(); i++) {
                Object tq = e.getTurns().get(i).get("query");
                q.append(" 轮").append(i + 1).append("：").append(tq instanceof String s ? s : "");
            }
            row.put("sampleType", "multi");
            row.put("query", q.toString());
            row.put("turnsCount", e.getTurns().size());
            row.put("turns", e.getTurns());
        } else {
            row.put("sampleType", "single");
            row.put("query", e.getQuery() == null ? "" : e.getQuery());
            row.put("turnsCount", 0);
            row.put("turns", List.of());
        }
        row.put("response", e.getResponse());
        row.put("referenceResponse", refView(e.getReferenceResponse()));
        row.put("tolerance", e.getTolerance());
        row.put("skills", e.getSkills());
        row.put("tools", e.getTools());
        row.put("mcps", e.getMcps());
        row.put("valid", true);
        return row;
    }

    /** 参考答案展示形态（同原 parseSampleRow：文本原样、非文本序列化为 JSON 串） */
    private String refView(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof String s) {
            return s;
        }
        return mapper.valueToTree(v).toString();
    }

    /** 按原文件行键序重建 raw JSONL 行（NULL 列省略键，与原 buildSampleLine 写出键序一致） */
    private String buildRaw(DatasetSampleEntity e) {
        ObjectNode n = mapper.createObjectNode();
        if (e.getSampleId() != null) {
            n.put("id", e.getSampleId());
        }
        if (e.getQuery() != null) {
            n.put("query", e.getQuery());
        }
        if (e.getResponse() != null) {
            n.put("response", e.getResponse());
        }
        if (e.getTurns() != null) {
            n.set("turns", mapper.valueToTree(e.getTurns()));
        }
        if (e.getReferenceResponse() != null) {
            n.set("reference_response", mapper.valueToTree(e.getReferenceResponse()));
        }
        if (e.getTolerance() != null) {
            n.put("tolerance", e.getTolerance());
        }
        if (e.getSkills() != null) {
            n.set("skills", mapper.valueToTree(e.getSkills()));
        }
        if (e.getTools() != null) {
            n.set("tools", mapper.valueToTree(e.getTools()));
        }
        if (e.getMcps() != null) {
            n.set("mcps", mapper.valueToTree(e.getMcps()));
        }
        if (e.getRubric() != null) {
            n.put("rubric", e.getRubric());
        }
        return n.toString();
    }

    // ---------------- 内部：写路径（校验与文案同原 buildSampleLine） ----------------

    /** 请求 → 样本实体（excludeRowId 为编辑场景排除自身；id 查重含 line-{seq} 伪 id，与原全行扫描同构） */
    private DatasetSampleEntity buildSampleEntity(String name, EvalSampleDTO req, Long excludeRowId) {
        String id = req.id() == null ? "" : req.id().trim();
        if (id.isEmpty()) {
            id = "q-" + STAMP.format(LocalDateTime.now()) + "-"
                    + Integer.toHexString((int) (System.currentTimeMillis() & 0xfff));
        }
        int seq = 0;
        for (DatasetSampleEntity r : rowsOf(name)) {
            seq++;
            if (excludeRowId != null && excludeRowId.equals(r.getId())) {
                continue;
            }
            String rid = r.getSampleId() != null ? r.getSampleId() : "line-" + seq;
            if (id.equals(rid)) {
                throw new ApiException(409, "id 已存在: " + id);
            }
        }
        DatasetSampleEntity e = new DatasetSampleEntity();
        e.setDatasetName(name);
        e.setSampleId(id);
        String sampleType = req.sampleType() == null ? "" : req.sampleType().trim().toLowerCase();
        List<EvalSampleDTO.TurnRequest> turns = req.turns() == null ? List.of() : req.turns();
        if (!sampleType.isEmpty() && !"single".equals(sampleType) && !"multi".equals(sampleType)) {
            throw new ApiException(400, "sampleType 仅支持 single 或 multi");
        }
        boolean isMulti = "multi".equals(sampleType) || (sampleType.isEmpty() && !turns.isEmpty());
        if (isMulti) {
            if (turns.size() < 2) {
                throw new ApiException(400, "多轮样本至少需要 2 轮");
            }
            List<Map<String, Object>> ts = new ArrayList<>();
            for (int i = 0; i < turns.size(); i++) {
                EvalSampleDTO.TurnRequest turn = turns.get(i);
                String q = turn == null || turn.query() == null ? "" : turn.query().trim();
                if (q.isBlank()) {
                    throw new ApiException(400, "第 " + (i + 1) + " 轮 query 不能为空");
                }
                Map<String, Object> t = new LinkedHashMap<>();
                t.put("query", q);
                if (turn != null && turn.response() != null && !turn.response().isBlank()) {
                    t.put("response", turn.response().trim());
                }
                ts.add(t);
            }
            e.setTurns(ts);
        } else {
            if (req.query() == null || req.query().isBlank()) {
                throw new ApiException(400, "query 不能为空");
            }
            e.setQuery(req.query().trim());
            if (req.response() != null && !req.response().isBlank()) {
                e.setResponse(req.response().trim());
            }
        }
        String type = req.referenceType() == null ? "" : req.referenceType().trim();
        String text = req.referenceText() == null ? "" : req.referenceText().trim();
        if (type.isEmpty() && !text.isEmpty()) {
            throw new ApiException(400, "已填写期望回答文本，但未选择 referenceType（string|number|array）");
        }
        if (!type.isEmpty()) {
            switch (type) {
                case "string" -> e.setReferenceResponse(text);
                case "number" -> {
                    try {
                        if (text.matches("-?\\d+")) {
                            e.setReferenceResponse(Long.parseLong(text));
                        } else {
                            e.setReferenceResponse(Double.parseDouble(text));
                        }
                    } catch (NumberFormatException ex) {
                        throw new ApiException(400, "数字类型解析失败: " + text);
                    }
                }
                case "array" -> {
                    JsonNode arr;
                    try {
                        arr = mapper.readTree(text);
                    } catch (Exception ex) {
                        throw new ApiException(400, "数组类型解析失败（须形如 [\"北宋\",\"唐代\"]）");
                    }
                    if (!arr.isArray()) {
                        throw new ApiException(400, "数组类型须形如 [\"北宋\",\"唐代\"]");
                    }
                    e.setReferenceResponse(mapper.convertValue(arr, Object.class));
                }
                default -> throw new ApiException(400, "未知 referenceType: " + type + "（string|number|array）");
            }
        }
        if (req.tolerance() != null) {
            e.setTolerance(req.tolerance());
        }
        e.setSkills(cleanList(req.skills()));
        e.setTools(cleanList(req.tools()));
        e.setMcps(cleanList(req.mcps()));
        return e;
    }

    /** 字符串清单清洗（语义同原 writeStringList：空/全空白 → 不写该键 → NULL） */
    private static List<String> cleanList(List<String> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        List<String> clean = list.stream()
                .filter(s -> s != null && !s.isBlank())
                .toList();
        return clean.isEmpty() ? null : clean;
    }

    /** turns JSON 列 → 多轮 record（行已过写路径/迁移校验，无需再抛错） */
    private static List<EvalSample.Turn> toTurns(List<Map<String, Object>> turns) {
        List<EvalSample.Turn> list = new ArrayList<>();
        for (Map<String, Object> t : turns) {
            Object q = t.get("query");
            Object r = t.get("response");
            list.add(new EvalSample.Turn(q instanceof String s ? s : "", r instanceof String s ? s : null));
        }
        return list;
    }
}
