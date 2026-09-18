package com.conca.ontology.eval.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.conca.ontology.agent.service.AgentSessionService;
import com.conca.ontology.eval.dto.request.EvalSampleRequest;
import com.conca.ontology.eval.dto.result.FromChatSampleResult;
import com.conca.ontology.eval.model.EvalSample;
import com.conca.ontology.shared.web.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

@Service
public class EvalDatasetService {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final Pattern DATASET_NAME_RE = Pattern.compile("^[A-Za-z0-9._-]+\\.jsonl$");

    private final ObjectMapper mapper;
    private final AgentSessionService agentSessionService;

    public EvalDatasetService(ObjectMapper mapper, AgentSessionService agentSessionService) {
        this.mapper = mapper;
        this.agentSessionService = agentSessionService;
    }

    @Value("${app.eval.dataset-dir:data/eval/datasets}")
    private String datasetDir;

    public Map<String, Object> listDatasets() {
        List<Map<String, Object>> list = new ArrayList<>();
        Path dir = datasetPath();
        if (!Files.isDirectory(dir)) {
            return Map.of("datasets", list);
        }
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString())).toList()) {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("name", p.getFileName().toString());
                d.put("count", countLines(p));
                list.add(d);
            }
        } catch (IOException e) {
            throw new ApiException(500, "扫描数据集目录失败: " + e.getMessage());
        }
        return Map.of("datasets", list);
    }

    public Map<String, Object> datasetPreview(String name, int limit) {
        List<Map<String, Object>> all = readAllSamples(requireDataset(name));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("samples", all.subList(0, Math.min(all.size(), Math.max(1, limit))));
        return m;
    }

    public Map<String, Object> datasetSamples(String name, int page, int pageSize) {
        List<Map<String, Object>> all = readAllSamples(requireDataset(name));
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
    public FromChatSampleResult sampleFromChat(String userId, String sessionId, int msgIndex) {
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
        return new FromChatSampleResult(sessionId, msgIndex, query, answer, ts, turnIndex, msgs.size());
    }

    public synchronized Map<String, Object> addSample(String name, EvalSampleRequest req) {
        Path p = requireDataset(name);
        String line = buildSampleLine(req, p, -1);
        try {
            Files.write(p, (line + "\n").getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new ApiException(500, "写入数据集失败: " + e.getMessage());
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("total", countLines(p));
        return m;
    }

    public synchronized Map<String, Object> updateSample(String name, int seq, EvalSampleRequest req) {
        Path p = requireDataset(name);
        List<String> lines = readPhysicalLines(p);
        int idx = indexOfSeq(lines, seq);
        if (idx < 0) {
            throw new ApiException(404, "样本不存在: 第 " + seq + " 条（数据集可能已被修改，请刷新）");
        }
        lines.set(idx, buildSampleLine(req, p, seq));
        rewrite(p, lines);
        return Map.of("name", name, "seq", seq);
    }

    public synchronized Map<String, Object> deleteSample(String name, int seq) {
        Path p = requireDataset(name);
        List<String> lines = readPhysicalLines(p);
        int idx = indexOfSeq(lines, seq);
        if (idx < 0) {
            throw new ApiException(404, "样本不存在: 第 " + seq + " 条（数据集可能已被修改，请刷新）");
        }
        lines.remove(idx);
        rewrite(p, lines);
        return Map.of("name", name, "seq", seq, "total", countLines(p));
    }

    public synchronized Map<String, Object> createDataset(String name) {
        if (name == null || !DATASET_NAME_RE.matcher(name).matches()) {
            throw new ApiException(400, "非法数据集文件名: " + name + "（须形如 my-ds.jsonl）");
        }
        Path p = datasetPath().resolve(name).normalize();
        if (!p.startsWith(datasetPath())) {
            throw new ApiException(400, "非法数据集文件名: " + name);
        }
        if (Files.exists(p)) {
            throw new ApiException(409, "数据集已存在: " + name);
        }
        try {
            Files.createDirectories(datasetPath());
            Files.createFile(p);
        } catch (IOException e) {
            throw new ApiException(500, "创建数据集失败: " + e.getMessage());
        }
        return Map.of("name", name, "count", 0);
    }

    public List<EvalSample> loadSamples(String dataset) throws IOException {
        Path p = requireDataset(dataset);
        List<EvalSample> list = new ArrayList<>();
        int lineNo = 0;
        try (var reader = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                if (line.isBlank()) {
                    continue;
                }
                try {
                    JsonNode node = mapper.readTree(line);
                    JsonNode ref = node.path("reference_response");
                    Object refObj = ref.isMissingNode() || ref.isNull()
                            ? null : mapper.convertValue(ref, Object.class);
                    List<EvalSample.Turn> turns = parseTurns(node.path("turns"));
                    list.add(new EvalSample(
                            node.path("id").asText("line-" + lineNo),
                            node.path("query").asText(""),
                            node.path("response").asText(null),
                            refObj,
                            node.path("tolerance").isNumber() ? node.path("tolerance").asDouble() : null,
                            node.path("rubric").asText(null),
                            turns));
                } catch (Exception e) {
                    list.add(new EvalSample("line-" + lineNo,
                            "[数据行解析失败] " + e.getMessage(), null, null, null, null, List.of()));
                }
            }
        }
        return list;
    }

    public Path datasetPath() {
        return Path.of(datasetDir).toAbsolutePath().normalize();
    }

    public Path requireDataset(String name) {
        if (name == null || !DATASET_NAME_RE.matcher(name).matches()) {
            throw new ApiException(400, "非法数据集文件名: " + name);
        }
        Path p = datasetPath().resolve(name).normalize();
        if (!p.startsWith(datasetPath())) {
            throw new ApiException(400, "非法数据集文件名: " + name);
        }
        if (!Files.isRegularFile(p)) {
            throw new ApiException(404, "数据集不存在: " + name);
        }
        return p;
    }

    public long countLines(Path p) {
        try (Stream<String> s = Files.lines(p, StandardCharsets.UTF_8)) {
            return s.filter(l -> !l.isBlank()).count();
        } catch (IOException e) {
            return -1;
        }
    }

    private List<Map<String, Object>> readAllSamples(Path p) {
        List<Map<String, Object>> samples = new ArrayList<>();
        int seq = 0;
        try (var reader = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                samples.add(parseSampleRow(line, ++seq));
            }
        } catch (IOException e) {
            throw new ApiException(500, "读取数据集失败: " + e.getMessage());
        }
        return samples;
    }

    private Map<String, Object> parseSampleRow(String line, int seq) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("seq", seq);
        row.put("raw", line);
        try {
            JsonNode node = mapper.readTree(line);
            row.put("id", node.path("id").asText("line-" + seq));
            JsonNode turnsNode = node.path("turns");
            if (turnsNode.isArray() && turnsNode.size() >= 2) {
                StringBuilder q = new StringBuilder("【多轮 " + turnsNode.size() + " 轮】");
                for (int i = 0; i < turnsNode.size(); i++) {
                    q.append(" 轮").append(i + 1).append("：").append(turnsNode.get(i).path("query").asText(""));
                }
                row.put("sampleType", "multi");
                row.put("query", q.toString());
                row.put("turnsCount", turnsNode.size());
                row.put("turns", mapper.convertValue(turnsNode, List.class));
            } else {
                row.put("sampleType", "single");
                row.put("query", node.path("query").asText(""));
                row.put("turnsCount", 0);
                row.put("turns", List.of());
            }
            row.put("response", node.path("response").asText(null));
            JsonNode ref = node.path("reference_response");
            row.put("referenceResponse",
                    ref.isMissingNode() || ref.isNull() ? null
                            : ref.isTextual() ? ref.asText() : mapper.writeValueAsString(ref));
            row.put("tolerance", node.path("tolerance").isNumber() ? node.path("tolerance").asDouble() : null);
            row.put("valid", true);
        } catch (Exception e) {
            row.put("valid", false);
            row.put("error", "JSON 解析失败: " + e.getMessage());
        }
        return row;
    }

    private String buildSampleLine(EvalSampleRequest req, Path p, int excludeSeq) {
        String id = req.id() == null ? "" : req.id().trim();
        if (id.isEmpty()) {
            id = "q-" + STAMP.format(LocalDateTime.now()) + "-"
                    + Integer.toHexString((int) (System.currentTimeMillis() & 0xfff));
        }
        for (Map<String, Object> r : readAllSamples(p)) {
            if ((int) r.get("seq") != excludeSeq && id.equals(r.get("id"))) {
                throw new ApiException(409, "id 已存在: " + id);
            }
        }
        try {
            ObjectNode node = mapper.createObjectNode();
            node.put("id", id);
            String sampleType = req.sampleType() == null ? "" : req.sampleType().trim().toLowerCase();
            List<EvalSampleRequest.TurnRequest> turns = req.turns() == null ? List.of() : req.turns();
            if (!sampleType.isEmpty() && !"single".equals(sampleType) && !"multi".equals(sampleType)) {
                throw new ApiException(400, "sampleType 仅支持 single 或 multi");
            }
            boolean isMulti = "multi".equals(sampleType) || (sampleType.isEmpty() && !turns.isEmpty());
            if (isMulti) {
                if (turns.size() < 2) {
                    throw new ApiException(400, "多轮样本至少需要 2 轮");
                }
                ArrayNode arr = node.putArray("turns");
                for (int i = 0; i < turns.size(); i++) {
                    EvalSampleRequest.TurnRequest turn = turns.get(i);
                    String q = turn == null || turn.query() == null ? "" : turn.query().trim();
                    if (q.isBlank()) {
                        throw new ApiException(400, "第 " + (i + 1) + " 轮 query 不能为空");
                    }
                    ObjectNode t = arr.addObject();
                    t.put("query", q);
                    if (turn != null && turn.response() != null && !turn.response().isBlank()) {
                        t.put("response", turn.response().trim());
                    }
                }
            } else {
                if (req.query() == null || req.query().isBlank()) {
                    throw new ApiException(400, "query 不能为空");
                }
                node.put("query", req.query().trim());
                if (req.response() != null && !req.response().isBlank()) {
                    node.put("response", req.response().trim());
                }
            }
            String type = req.referenceType() == null ? "" : req.referenceType().trim();
            if (!type.isEmpty()) {
                String text = req.referenceText() == null ? "" : req.referenceText().trim();
                switch (type) {
                    case "string" -> node.put("reference_response", text);
                    case "number" -> {
                        try {
                            if (text.matches("-?\\d+")) {
                                node.put("reference_response", Long.parseLong(text));
                            } else {
                                node.put("reference_response", Double.parseDouble(text));
                            }
                        } catch (NumberFormatException e) {
                            throw new ApiException(400, "数字类型解析失败: " + text);
                        }
                    }
                    case "array" -> {
                        JsonNode arr;
                        try {
                            arr = mapper.readTree(text);
                        } catch (Exception e) {
                            throw new ApiException(400, "数组类型解析失败（须形如 [\"北宋\",\"唐代\"]）");
                        }
                        if (!arr.isArray()) {
                            throw new ApiException(400, "数组类型须形如 [\"北宋\",\"唐代\"]");
                        }
                        node.set("reference_response", arr);
                    }
                    default -> throw new ApiException(400, "未知 referenceType: " + type + "（string|number|array）");
                }
            }
            if (req.tolerance() != null) {
                node.put("tolerance", req.tolerance());
            }
            return mapper.writeValueAsString(node);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(500, "样本序列化失败: " + e.getMessage());
        }
    }

    private List<String> readPhysicalLines(Path p) {
        try {
            return Files.readAllLines(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ApiException(500, "读取数据集失败: " + e.getMessage());
        }
    }

    private int indexOfSeq(List<String> lines, int seq) {
        int n = 0;
        for (int i = 0; i < lines.size(); i++) {
            if (!lines.get(i).isBlank() && ++n == seq) {
                return i;
            }
        }
        return -1;
    }

    private void rewrite(Path p, List<String> lines) {
        Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
        try {
            Files.write(tmp, lines, StandardCharsets.UTF_8);
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignore) {
            }
            throw new ApiException(500, "写入数据集失败: " + e.getMessage());
        }
    }

    private List<EvalSample.Turn> parseTurns(JsonNode turnsNode) {
        if (turnsNode == null || !turnsNode.isArray()) {
            return List.of();
        }
        if (turnsNode.size() == 1) {
            throw new IllegalArgumentException("turns 仅 1 轮，请改用单轮 query/response 字段");
        }
        List<EvalSample.Turn> turns = new ArrayList<>();
        for (JsonNode t : turnsNode) {
            String q = t.path("query").asText("");
            if (q.isBlank()) {
                throw new IllegalArgumentException("turns 中存在 query 为空的轮次");
            }
            turns.add(new EvalSample.Turn(q, t.path("response").asText(null)));
        }
        return turns;
    }
}
