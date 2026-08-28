package com.conca.ontology.eval;

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
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.conca.ontology.dto.request.EvalSampleRequest;
import com.conca.ontology.exception.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultDataDeltaEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.harness.agent.HarnessAgent;

/**
 * 评测编排服务（工程模式对齐 BackupService：异步任务 + 内存进度 + 结果落盘 + 单任务互斥）：
 * 数据集（data/eval/datasets/*.jsonl，格式对齐 OpenJudge：query/response/reference_response）
 * 逐条驱动 HarnessAgent（服务端直接收集事件流，不走前端 SSE；样本预置 response 时跳过
 * agent 直接判分，即 OpenJudge 纯判分模式），轨迹与回答交由 Grader 判分，
 * 结果写 data/eval/results/eval-*.json。
 * 单条样本/单个评测器异常均转为 error 结果，不中断整批（对齐 OpenJudge GraderError 语义）。
 */
@Service
public class EvalService {

    private static final Logger log = LoggerFactory.getLogger(EvalService.class);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    /** 单样本 agent 调用上限（多轮工具调用场景留足余量） */
    private static final long AGENT_TIMEOUT_MINUTES = 5;
    /** 数据集文件名（防路径穿越）与任务 id / 结果文件名 */
    private static final Pattern DATASET_NAME_RE = Pattern.compile("^[A-Za-z0-9._-]+\\.jsonl$");
    private static final Pattern RUN_ID_RE = Pattern.compile("^eval-\\d{8}-\\d{6}-[0-9a-f]{3}$");
    /** 结果文件名（RUN_ID + .json；独立于 RUN_ID_RE，避免锚点 pattern 拼接后永不匹配） */
    private static final Pattern RUN_FILE_RE = Pattern.compile("^eval-\\d{8}-\\d{6}-[0-9a-f]{3}\\.json$");
    private static final int TRUNCATE = 500;

    /** 进度任务（内存态；产物落盘不受服务重启影响，历史任务从结果文件读取） */
    public static final class Task {
        public final String id;
        public final String dataset;
        public final List<String> graderNames;
        public final String userId;
        /** LLM 判分次数（B8 运行参数，null = 未指定回落 app.eval.judge.runs 配置） */
        public final Integer judgeRuns;
        public final LocalDateTime startedAt = LocalDateTime.now();
        volatile String status = "running";  // running | success | failed
        volatile String detail = "准备中";
        volatile int percent = 0;
        volatile String error;
        volatile Map<String, Object> result;
        volatile String resultFile;

        Task(String id, String dataset, List<String> graderNames, Integer judgeRuns, String userId) {
            this.id = id;
            this.dataset = dataset;
            this.graderNames = graderNames;
            this.judgeRuns = judgeRuns;
            this.userId = userId;
        }
    }

    private final ObjectProvider<HarnessAgent> agentProvider;
    private final ObjectMapper mapper;
    private final JudgeClient judgeClient;
    private final Map<String, Grader> graders = new TreeMap<>();

    @Value("${app.eval.dataset-dir:data/eval/datasets}")
    private String datasetDir;

    @Value("${app.eval.result-dir:data/eval/results}")
    private String resultDir;

    private final AtomicReference<Task> current = new AtomicReference<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "eval-worker");
        t.setDaemon(true);
        return t;
    });

    public EvalService(ObjectProvider<HarnessAgent> agentProvider,
            ObjectMapper mapper, JudgeClient judgeClient, List<Grader> graderBeans) {
        this.agentProvider = agentProvider;
        this.mapper = mapper;
        this.judgeClient = judgeClient;
        for (Grader g : graderBeans) {
            graders.put(g.name(), g);
        }
    }

    // ---------------- 查询：数据集 / 评测器 ----------------

    /** 数据集列表（目录扫描，逐文件数行数；目录不存在视为空） */
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

    /** 数据集预览（前 limit 行，逐行解析；坏行标记 error 不中断） */
    public Map<String, Object> datasetPreview(String name, int limit) {
        List<Map<String, Object>> all = readAllSamples(requireDataset(name));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("samples", all.subList(0, Math.min(all.size(), Math.max(1, limit))));
        return m;
    }

    /** 评测器清单（注册的 Spring Bean 即页面可选集） */
    public Map<String, Object> graderList() {
        List<Map<String, Object>> list = new ArrayList<>();
        graders.forEach((name, g) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", g.name());
            m.put("description", g.description());
            m.put("type", g.type());
            list.add(m);
        });
        return Map.of("graders", list);
    }

    // ---------------- 数据集管理：分页读取 / 增删改 / 新建 ----------------

    /** 分页读取样本（seq = 非空行序号，从 1 起，编辑/删除按此定位；page 越界自动收敛） */
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

    /** 新增一条样本（校验后追加一行） */
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

    /** 编辑第 seq 个非空行（整读整写 + tmp 原子替换；运行中评测任务已在启动时载入内存，不受影响） */
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

    /** 删除第 seq 个非空行 */
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

    /** 新建空数据集文件（同名已存在 409） */
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

    /** 全量读取样本（seq = 非空行序号，从 1 起；坏行 valid=false 不中断） */
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

    /** 解析一行样本（与预览字段一致，附 seq；raw 供坏行编辑时展示原文） */
    private Map<String, Object> parseSampleRow(String line, int seq) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("seq", seq);
        row.put("raw", line);
        try {
            JsonNode node = mapper.readTree(line);
            row.put("id", node.path("id").asText("line-" + seq));
            JsonNode turnsNode = node.path("turns");
            if (turnsNode.isArray() && turnsNode.size() >= 2) {
                // 多轮样本：query 展示为各轮串联（编辑器暂不支持 turns，编辑保存将转为单轮）
                StringBuilder q = new StringBuilder("【多轮 " + turnsNode.size() + " 轮】");
                for (int i = 0; i < turnsNode.size(); i++) {
                    q.append(" 轮").append(i + 1).append("：").append(turnsNode.get(i).path("query").asText(""));
                }
                row.put("query", q.toString());
                row.put("turns", turnsNode.size());
            } else {
                row.put("query", node.path("query").asText(""));
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

    /** 构建并校验一行样本 JSON（query 必填；ref 按类型解析；id 须唯一；样本不含评测器字段） */
    private String buildSampleLine(EvalSampleRequest req, Path p, int excludeSeq) {
        if (req.query() == null || req.query().isBlank()) {
            throw new ApiException(400, "query 不能为空");
        }
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
            node.put("query", req.query().trim());
            if (req.response() != null && !req.response().isBlank()) {
                node.put("response", req.response().trim());
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

    /** 物理行全量读取（保留空行原样） */
    private List<String> readPhysicalLines(Path p) {
        try {
            return Files.readAllLines(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ApiException(500, "读取数据集失败: " + e.getMessage());
        }
    }

    /** 第 seq 个非空行的物理下标（无则 -1） */
    private int indexOfSeq(List<String> lines, int seq) {
        int n = 0;
        for (int i = 0; i < lines.size(); i++) {
            if (!lines.get(i).isBlank() && ++n == seq) {
                return i;
            }
        }
        return -1;
    }

    /** 原子重写（tmp 后 rename，读方只会看到旧或新完整文件） */
    private void rewrite(Path p, List<String> lines) {
        Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
        try {
            Files.write(tmp, lines, StandardCharsets.UTF_8);
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignore) {
                // 清理失败不影响主错误上报
            }
            throw new ApiException(500, "写入数据集失败: " + e.getMessage());
        }
    }

    // ---------------- 任务控制 ----------------

    /** 启动评测（异步），同一时间仅允许一个任务；judgeRuns 为 LLM 判分次数运行参数（null/1 = 单次） */
    public synchronized Map<String, Object> start(String dataset, List<String> graderNames, Integer judgeRuns, String userId) {
        Task t = current.get();
        if (t != null && "running".equals(t.status)) {
            throw new ApiException(409, "已有评测任务进行中，请等待完成");
        }
        Integer runs = null;
        if (judgeRuns != null) {
            if (judgeRuns < 1 || judgeRuns > 3) {
                throw new ApiException(400, "judgeRuns 须在 1-3 之间（多次判分取平均降噪，judge 调用成本随次数线性增长）");
            }
            if (judgeRuns > 1) {
                runs = judgeRuns;
            }
        }
        requireDataset(dataset);
        List<String> names = graderNames == null ? List.of() : graderNames.stream().filter(n -> n != null && !n.isBlank()).toList();
        if (names.isEmpty()) {
            // 评测器仅任务级选择（样本不指定）：必选至少一个，避免误跑全量 LLM 判分产生 token 成本
            throw new ApiException(400, "请至少勾选一个评测器（可用：" + graders.keySet() + "）");
        }
        for (String n : names) {
            if (!graders.containsKey(n)) {
                throw new ApiException(400, "未知评测器: " + n + "（可用：" + graders.keySet() + "）");
            }
        }
        // LLM 判分依赖 judge 通道：未配置 key 时提前 409（对齐现有「AI 服务未配置」文案约定）
        if (names.stream().anyMatch(n -> "llm".equals(graders.get(n).type())) && !judgeClient.isConfigured()) {
            throw new ApiException(409, "AI 服务未配置（缺少 OPENROUTER_API_KEY）：所选评测器包含 LLM 判分，"
                    + "请配置后重启服务或仅勾选规则评测器");
        }
        // 仅当存在需 agent 作答的样本（未预置 response）时才要求 agent 装配；
        // 全部预置 response 的数据集为纯判分模式（对齐 OpenJudge），不依赖 AI 服务
        boolean needAgent;
        try {
            needAgent = loadSamples(dataset).stream().anyMatch(s -> !s.hasPresetResponse());
        } catch (IOException e) {
            throw new ApiException(500, "读取数据集失败: " + e.getMessage());
        }
        if (needAgent && agentProvider.getIfAvailable() == null) {
            throw new ApiException(409, "AI 服务未配置（缺少 OPENROUTER_API_KEY）：数据集含需 agent 作答的样本，"
                    + "请配置后重启服务或改用全部预置 response 的数据集");
        }
        String id = "eval-" + LocalDateTime.now().format(STAMP) + "-"
                + Integer.toHexString((int) (Math.random() * 0xfff));
        Task task = new Task(id, dataset, names, runs, userId);
        current.set(task);
        executor.submit(() -> runEval(task));
        log.info("eval started: {} dataset={} graders={}", task.id, dataset, names);
        return Map.of("taskId", task.id);
    }

    /** 任务进度/详情：内存命中优先（含运行中实时态），否则从结果文件读取（服务重启后的历史） */
    public Map<String, Object> progress(String taskId) {
        Task t = current.get();
        if (t != null && t.id.equals(taskId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("taskId", t.id);
            m.put("status", t.status);
            m.put("detail", t.detail);
            m.put("percent", t.percent);
            m.put("error", t.error);
            m.put("resultFile", t.resultFile);
            if (t.result != null) {
                m.put("result", t.result);
            }
            return m;
        }
        Map<String, Object> saved = readResult(taskId);
        if (saved == null) {
            throw new ApiException(404, "task not found: " + taskId);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("taskId", taskId);
        m.put("status", "success");
        m.put("detail", "历史任务（从结果文件读取）");
        m.put("percent", 100);
        m.put("error", null);
        m.put("resultFile", taskId + ".json");
        m.put("result", saved);
        return m;
    }

    /** 历史任务列表（扫描结果目录，按文件名倒序 = 时间倒序） */
    public Map<String, Object> listRuns() {
        List<Map<String, Object>> runs = new ArrayList<>();
        Path dir = resultPath();
        if (!Files.isDirectory(dir)) {
            return Map.of("runs", runs);
        }
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.filter(Files::isRegularFile)
                    .filter(p -> RUN_FILE_RE.matcher(p.getFileName().toString()).matches())
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString(), Comparator.reverseOrder()))
                    .toList()) {
                String id = p.getFileName().toString().replaceFirst("\\.json$", "");
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", id);
                try {
                    Map<String, Object> saved = mapper.readValue(p.toFile(), Map.class);
                    m.put("dataset", saved.get("dataset"));
                    m.put("finishedAt", saved.get("finishedAt"));
                    m.put("summary", saved.get("summary"));
                } catch (Exception e) {
                    m.put("error", "结果文件解析失败: " + e.getMessage());
                }
                runs.add(m);
            }
        } catch (IOException e) {
            throw new ApiException(500, "扫描评测结果目录失败: " + e.getMessage());
        }
        return Map.of("runs", runs);
    }

    /** 下载用结果文件路径（id 正则校验防路径穿越） */
    public Path requireResultFile(String taskId) {
        if (taskId == null || !RUN_ID_RE.matcher(taskId).matches()) {
            throw new ApiException(400, "非法任务 id: " + taskId);
        }
        Path p = resultPath().resolve(taskId + ".json").normalize();
        if (!p.startsWith(resultPath())) {
            throw new ApiException(400, "非法任务 id: " + taskId);
        }
        if (!Files.isRegularFile(p)) {
            throw new ApiException(404, "评测结果不存在: " + taskId);
        }
        return p;
    }

    // ---------------- 评测执行 ----------------

    private void runEval(Task task) {
        try {
            List<EvalSample> samples = loadSamples(task.dataset);
            int total = samples.size();
            task.detail = "共 " + total + " 条样本，开始评测";
            List<Map<String, Object>> sampleResults = new ArrayList<>();
            long totalDuration = 0;
            long totalPromptTokens = 0;
            long totalCompletionTokens = 0;

            for (int i = 0; i < total; i++) {
                EvalSample sample = samples.get(i);
                task.detail = "评测 " + (i + 1) + "/" + total + "：" + brief(displayQuery(sample));
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", sample.id());
                row.put("query", displayQuery(sample));
                row.put("referenceResponse", sample.referenceResponse());
                row.put("tolerance", sample.tolerance());
                if (sample.isMultiTurn()) {
                    row.put("turns", sample.turns().size());
                }
                try {
                    // 预置 response 纯判分模式（对齐 OpenJudge）：跳过 agent，占位 record 仅承载回答；
                    // 多轮样本（turns）预置时同样跳过 agent，dialogue 取预置脚本
                    AgentRecord record;
                    if (sample.hasPresetResponse()) {
                        if (sample.isMultiTurn()) {
                            List<Map<String, Object>> dialogue = new ArrayList<>();
                            for (EvalSample.Turn t : sample.turns()) {
                                dialogue.add(Map.of("query", t.query() == null ? "" : t.query(),
                                        "answer", t.response() == null ? "" : t.response()));
                            }
                            record = new AgentRecord(sample.turns().get(sample.turns().size() - 1).response(),
                                    List.of(), 0, 0, 0, 0, null, dialogue);
                        } else {
                            record = new AgentRecord(sample.response(), List.of(), 0, 0, 0, 0, null);
                        }
                        row.put("responseSource", "preset");
                    } else if (sample.isMultiTurn()) {
                        // 多轮 agent 模式：轮序在同一会话连续作答（跨轮上下文记忆），被评测对象为最后一轮回答
                        record = askAgentMultiTurn(sample, task);
                        row.put("responseSource", "agent");
                    } else {
                        record = askAgent(sample.query(), task);
                        row.put("responseSource", "agent");
                    }
                    totalDuration += record.durationMs();
                    totalPromptTokens += record.promptTokens();
                    totalCompletionTokens += record.completionTokens();
                    row.put("answer", record.answer());
                    row.put("toolCalls", record.toolCalls());
                    if (!record.dialogue().isEmpty()) {
                        row.put("dialogue", record.dialogue());
                    }
                    row.put("rounds", record.rounds());
                    row.put("durationMs", record.durationMs());
                    row.put("promptTokens", record.promptTokens());
                    row.put("completionTokens", record.completionTokens());
                    row.put("agentError", record.error());
                    row.put("scores", judge(sample, record, task));
                } catch (Exception e) {
                    log.warn("eval sample {} failed: {}", sample.id(), e.getMessage());
                    row.put("agentError", e.getMessage());
                    row.put("scores", List.of(GraderScore.fail("agent", "agent 调用异常：" + e.getMessage())));
                }
                sampleResults.add(row);
                task.percent = (int) (95.0 * (i + 1) / Math.max(total, 1));
            }

            task.detail = "汇总结果并落盘";
            Map<String, Object> summary = summarize(sampleResults, totalDuration,
                    totalPromptTokens, totalCompletionTokens);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", task.id);
            result.put("dataset", task.dataset);
            result.put("graders", task.graderNames);
            result.put("judgeRuns", task.judgeRuns);
            result.put("startedAt", task.startedAt.toString());
            result.put("finishedAt", LocalDateTime.now().toString());
            result.put("summary", summary);
            result.put("samples", sampleResults);
            writeResult(task, result);

            task.result = result;
            task.resultFile = task.id + ".json";
            task.detail = "评测完成";
            task.percent = 100;
            task.status = "success";
            log.info("eval finished: {} passRate={} avgScore={}",
                    task.id, summary.get("passRate"), summary.get("avgScore"));
        } catch (Exception e) {
            task.status = "failed";
            task.error = e.getMessage();
            log.error("eval failed: {}", task.id, e);
        }
    }

    /**
     * 样本 × 评测器逐个判分：单评测器异常转 GraderScore.fail，不影响其它评测器。
     * 评测器选择 = 任务级勾选（task.graderNames，启动时已校验非空且均已注册），样本不指定评测器。
     * mapper 落点（对齐 OpenJudge grader_configs.mapper，数据集字段 → grader 参数名）：
     * query→query、reference_response→reference_response、tolerance→tolerance、
     * rubric→rubric 为恒等映射；response 由运行时注入（预置样本取数据集 response，
     * agent 样本取 record.answer）。
     */
    private List<Map<String, Object>> judge(EvalSample sample, AgentRecord record, Task task) {
        List<String> names = task.graderNames;
        Map<String, Object> params = new LinkedHashMap<>();
        // 多轮样本被评测对象为最后一轮回答，query 取最后一轮问题（完整对话在 record.dialogue）
        params.put("query", sample.isMultiTurn()
                ? sample.turns().get(sample.turns().size() - 1).query() : sample.query());
        params.put("response", record.answer());
        params.put("reference_response", sample.referenceResponse());
        params.put("tolerance", sample.tolerance());
        params.put("rubric", sample.rubric());
        if (task.judgeRuns != null) {
            params.put("judge_runs", task.judgeRuns);
        }
        GraderInput input = new GraderInput(params, record);
        List<Map<String, Object>> scores = new ArrayList<>();
        for (String n : names) {
            GraderScore score;
            try {
                score = graders.get(n).evaluate(input);
            } catch (Exception e) {
                score = GraderScore.fail(n, "评测器执行异常：" + e.getMessage());
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", score.name());
            m.put("score", score.score());
            m.put("reason", score.reason());
            m.put("error", score.error());
            m.put("metadata", score.metadata());
            scores.add(m);
        }
        return scores;
    }

    /** 汇总：passRate 按样本级均分 ≥ 0.8 记通过；errorCount 含 agent 调用失败与评测器失败；
     *  judge 汇总 LLM 评测器的判分调用成本（tokens/次数/模型，从各 score 的 metadata.judge 累计） */
    private Map<String, Object> summarize(List<Map<String, Object>> sampleResults,
            long totalDuration, long totalPromptTokens, long totalCompletionTokens) {
        int total = sampleResults.size();
        int pass = 0;
        int errorSamples = 0;
        double scoreSum = 0;
        int scoreCount = 0;
        long judgePromptTokens = 0;
        long judgeCompletionTokens = 0;
        int judgeCalls = 0;
        String judgeModel = null;
        for (Map<String, Object> row : sampleResults) {
            List<Map<String, Object>> scores = (List<Map<String, Object>>) row.get("scores");
            double rowSum = 0;
            int rowCount = 0;
            boolean rowError = false;
            for (Map<String, Object> s : scores) {
                if (s.get("metadata") instanceof Map<?, ?> mm && mm.get("judge") instanceof Map<?, ?> j) {
                    judgePromptTokens += num(j.get("promptTokens"));
                    judgeCompletionTokens += num(j.get("completionTokens"));
                    judgeCalls += num(j.get("calls"));
                    if (j.get("model") instanceof String ms && !ms.isBlank()) {
                        judgeModel = ms;
                    }
                }
                if (Boolean.TRUE.equals(s.get("error"))) {
                    rowError = true;
                    continue;
                }
                rowSum += ((Number) s.get("score")).doubleValue();
                rowCount++;
            }
            if (rowError) {
                errorSamples++;
            }
            if (rowCount > 0) {
                double avg = rowSum / rowCount;
                scoreSum += avg;
                scoreCount++;
                if (avg >= 0.8 && !rowError) {
                    pass++;
                }
            }
        }
        Map<String, Object> judge = new LinkedHashMap<>();
        judge.put("model", judgeModel);
        judge.put("calls", judgeCalls);
        judge.put("promptTokens", judgePromptTokens);
        judge.put("completionTokens", judgeCompletionTokens);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", total);
        m.put("passCount", pass);
        m.put("passRate", total == 0 ? 0 : Math.round(pass * 1000.0 / total) / 1000.0);
        m.put("avgScore", scoreCount == 0 ? 0 : Math.round(scoreSum / scoreCount * 1000.0) / 1000.0);
        m.put("errorCount", errorSamples);
        m.put("avgDurationMs", total == 0 ? 0 : totalDuration / total);
        m.put("promptTokens", totalPromptTokens);
        m.put("completionTokens", totalCompletionTokens);
        m.put("judge", judge);
        return m;
    }

    private static long num(Object v) {
        return v instanceof Number n ? n.longValue() : 0;
    }

    // ---------------- agent 驱动与轨迹采集 ----------------

    /** 多轮样本展示用 query：各轮问题串联（轮1：… ◆ 轮2：…） */
    private static String displayQuery(EvalSample sample) {
        if (!sample.isMultiTurn()) {
            return sample.query();
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sample.turns().size(); i++) {
            if (i > 0) {
                sb.append(" ◆ ");
            }
            sb.append("轮").append(i + 1).append("：").append(sample.turns().get(i).query());
        }
        return sb.toString();
    }

    /**
     * 多轮样本驱动：轮序在同一会话（sessionId=task.id，与 chat 页同一机制）连续作答，
     * 跨轮上下文记忆生效；tokens/轮次/工具轨迹累计，answer 为最后一轮回答，
     * dialogue 承载全轮对话（多轮评测器的证据）。任一轮失败即中止（后续轮上下文已不可信）。
     */
    private AgentRecord askAgentMultiTurn(EvalSample sample, Task task) {
        List<Map<String, Object>> dialogue = new ArrayList<>();
        List<Map<String, Object>> allToolCalls = new ArrayList<>();
        int rounds = 0;
        long promptTokens = 0;
        long completionTokens = 0;
        long durationMs = 0;
        String lastAnswer = "";
        for (int i = 0; i < sample.turns().size(); i++) {
            EvalSample.Turn turn = sample.turns().get(i);
            task.detail = "评测 " + displayQuery(sample) + " —— 第 " + (i + 1) + "/" + sample.turns().size() + " 轮";
            AgentRecord r = askAgent(turn.query(), task);
            rounds += r.rounds();
            promptTokens += r.promptTokens();
            completionTokens += r.completionTokens();
            durationMs += r.durationMs();
            if (r.toolCalls() != null) {
                allToolCalls.addAll(r.toolCalls());
            }
            lastAnswer = r.answer();
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("query", turn.query());
            d.put("answer", r.answer());
            dialogue.add(d);
            if (r.failed()) {
                return new AgentRecord(lastAnswer, allToolCalls, rounds, promptTokens,
                        completionTokens, durationMs, "第 " + (i + 1) + " 轮失败：" + r.error(), dialogue);
            }
        }
        return new AgentRecord(lastAnswer, allToolCalls, rounds, promptTokens,
                completionTokens, durationMs, null, dialogue);
    }

    /** 订阅 HarnessAgent 事件流（collectList 阻塞等待完成），采集回答全文与工具轨迹 */
    private AgentRecord askAgent(String query, Task task) {
        long t0 = System.currentTimeMillis();
        HarnessAgent agent = agentProvider.getIfAvailable();
        StringBuilder answer = new StringBuilder();
        Map<String, Map<String, Object>> toolBuf = new LinkedHashMap<>();
        int[] rounds = {0};
        long[] promptTokens = {0};
        long[] completionTokens = {0};

        if (agent == null) {
            return new AgentRecord("", List.of(), 0, 0, 0, 0,
                    "AI 服务未配置（agent 未装配）");
        }
        Msg userMsg = Msg.builder().role(MsgRole.USER).textContent(query).build();
        RuntimeContext ctx = RuntimeContext.builder()
                .userId(task.userId)
                .sessionId(task.id)
                .build();
        try {
            List<AgentEvent> events = agent.streamEvents(userMsg, ctx)
                    .collectList()
                    .block(java.time.Duration.ofMinutes(AGENT_TIMEOUT_MINUTES));
            if (events == null) {
                events = List.of();
            }
            for (AgentEvent event : events) {
                switch (event.getType()) {
                    case MODEL_CALL_START -> rounds[0]++;
                    case MODEL_CALL_END -> collectUsage((ModelCallEndEvent) event, promptTokens, completionTokens);
                    case TEXT_BLOCK_DELTA -> answer.append(((TextBlockDeltaEvent) event).getDelta());
                    case TOOL_CALL_START -> {
                        ToolCallStartEvent e = (ToolCallStartEvent) event;
                        Map<String, Object> t = new LinkedHashMap<>();
                        t.put("name", e.getToolCallName());
                        t.put("args", new StringBuilder());
                        t.put("result", new StringBuilder());
                        t.put("state", "");
                        toolBuf.put(e.getToolCallId(), t);
                    }
                    case TOOL_CALL_DELTA -> appendBuf(toolBuf, ((ToolCallDeltaEvent) event).getToolCallId(),
                            "args", ((ToolCallDeltaEvent) event).getDelta());
                    case TOOL_RESULT_TEXT_DELTA -> appendBuf(toolBuf,
                            ((ToolResultTextDeltaEvent) event).getToolCallId(),
                            "result", ((ToolResultTextDeltaEvent) event).getDelta());
                    case TOOL_RESULT_DATA_DELTA -> appendBuf(toolBuf,
                            ((ToolResultDataDeltaEvent) event).getToolCallId(),
                            "result", dataDeltaText(((ToolResultDataDeltaEvent) event).getData()));
                    case TOOL_RESULT_END -> {
                        ToolResultEndEvent e = (ToolResultEndEvent) event;
                        Map<String, Object> t = toolBuf.get(e.getToolCallId());
                        if (t != null) {
                            t.put("state", e.getState() == null ? "" : e.getState().name());
                        }
                    }
                    case AGENT_RESULT -> {
                        AgentResultEvent e = (AgentResultEvent) event;
                        // 仅当轮次累计缺失时兜底（AGENT_RESULT 的 usage 是最终消息的，直接覆盖会丢多轮累计）
                        if (rounds[0] == 0 && e.getResult() != null && e.getResult().getUsage() != null) {
                            promptTokens[0] = e.getResult().getUsage().getInputTokens();
                            completionTokens[0] = e.getResult().getUsage().getOutputTokens();
                        }
                    }
                    default -> { /* thinking 等事件一期轨迹不采集 */ }
                }
            }
        } catch (Exception e) {
            return new AgentRecord(answer.toString(), toToolCalls(toolBuf), rounds[0],
                    promptTokens[0], completionTokens[0], System.currentTimeMillis() - t0,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
        return new AgentRecord(answer.toString(), toToolCalls(toolBuf), rounds[0],
                promptTokens[0], completionTokens[0], System.currentTimeMillis() - t0, null);
    }

    private static void collectUsage(ModelCallEndEvent event, long[] promptTokens, long[] completionTokens) {
        if (event.getUsage() != null) {
            promptTokens[0] += event.getUsage().getInputTokens();
            completionTokens[0] += event.getUsage().getOutputTokens();
        }
    }

    private static void appendBuf(Map<String, Map<String, Object>> toolBuf, String id, String field, String delta) {
        Map<String, Object> t = toolBuf.get(id);
        if (t != null && delta != null && !delta.isEmpty()) {
            ((StringBuilder) t.get(field)).append(delta);
        }
    }

    /** 轨迹定稿：args/result 截断，保持调用顺序 */
    private static List<Map<String, Object>> toToolCalls(Map<String, Map<String, Object>> toolBuf) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<String, Object> t : toolBuf.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", t.get("name"));
            m.put("args", truncate(((StringBuilder) t.get("args")).toString()));
            m.put("result", truncate(((StringBuilder) t.get("result")).toString()));
            m.put("state", t.get("state"));
            list.add(m);
        }
        return list;
    }

    private static String truncate(String s) {
        if (s == null || s.length() <= TRUNCATE) {
            return s == null ? "" : s;
        }
        return s.substring(0, TRUNCATE) + "…（共" + s.length() + "字符）";
    }

    private static String dataDeltaText(ContentBlock data) {
        if (data instanceof TextBlock tb) {
            return tb.getText() == null ? "" : tb.getText();
        }
        return data == null ? "" : "[" + data.getClass().getSimpleName() + "]";
    }

    // ---------------- 数据集与结果文件 ----------------

    /** 加载数据集：坏行转为带 error 标记的占位样本（评测结果中可见，不中断整批） */
    private List<EvalSample> loadSamples(String dataset) throws IOException {
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

    /** 解析多轮脚本 turns（缺失/非数组返回空列表 = 单轮样本；≥2 轮方为多轮，轮内 query 必填） */
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

    /** 结果落盘（先 .tmp 后 rename，半成品不进列表） */
    private void writeResult(Task task, Map<String, Object> result) throws IOException {
        Path dir = Files.createDirectories(resultPath());
        Path tmp = dir.resolve(task.id + ".json.tmp");
        Path fin = dir.resolve(task.id + ".json");
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), result);
            Files.move(tmp, fin, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private Map<String, Object> readResult(String taskId) {
        if (taskId == null || !RUN_ID_RE.matcher(taskId).matches()) {
            return null;
        }
        Path p = resultPath().resolve(taskId + ".json");
        if (!Files.isRegularFile(p)) {
            return null;
        }
        try {
            return mapper.readValue(p.toFile(), Map.class);
        } catch (IOException e) {
            throw new ApiException(500, "评测结果文件读取失败: " + e.getMessage());
        }
    }

    /** 数据集文件名校验 + 存在性（防路径穿越） */
    private Path requireDataset(String name) {
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

    private long countLines(Path p) {
        try (Stream<String> s = Files.lines(p, StandardCharsets.UTF_8)) {
            return s.filter(l -> !l.isBlank()).count();
        } catch (IOException e) {
            return -1;
        }
    }

    private static String brief(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 30 ? s : s.substring(0, 30) + "…";
    }

    private Path datasetPath() {
        return Path.of(datasetDir).toAbsolutePath().normalize();
    }

    private Path resultPath() {
        return Path.of(resultDir).toAbsolutePath().normalize();
    }
}
