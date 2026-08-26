package com.conca.ontology.eval;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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

import com.conca.ontology.exception.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
        public final LocalDateTime startedAt = LocalDateTime.now();
        volatile String status = "running";  // running | success | failed
        volatile String detail = "准备中";
        volatile int percent = 0;
        volatile String error;
        volatile Map<String, Object> result;
        volatile String resultFile;

        Task(String id, String dataset, List<String> graderNames, String userId) {
            this.id = id;
            this.dataset = dataset;
            this.graderNames = graderNames;
            this.userId = userId;
        }
    }

    private final ObjectProvider<HarnessAgent> agentProvider;
    private final ObjectMapper mapper;
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
            ObjectMapper mapper, List<Grader> graderBeans) {
        this.agentProvider = agentProvider;
        this.mapper = mapper;
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
        Path p = requireDataset(name);
        List<Map<String, Object>> samples = new ArrayList<>();
        int lineNo = 0;
        try (var reader = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            String line;
            while (samples.size() < Math.max(1, limit) && (line = reader.readLine()) != null) {
                lineNo++;
                if (line.isBlank()) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                try {
                    JsonNode node = mapper.readTree(line);
                    row.put("id", node.path("id").asText("line-" + lineNo));
                    row.put("query", node.path("query").asText(""));
                    row.put("response", node.path("response").asText(null));
                    JsonNode ref = node.path("reference_response");
                    row.put("referenceResponse",
                            ref.isMissingNode() || ref.isNull() ? null
                                    : ref.isTextual() ? ref.asText() : mapper.writeValueAsString(ref));
                    row.put("tolerance", node.path("tolerance").isNumber() ? node.path("tolerance").asDouble() : null);
                    ArrayNode g = (ArrayNode) node.path("graders");
                    List<String> names = new ArrayList<>();
                    if (g != null && g.isArray()) {
                        g.forEach(n -> names.add(n.asText()));
                    }
                    row.put("graders", names);
                    row.put("valid", true);
                } catch (Exception e) {
                    row.put("valid", false);
                    row.put("error", "JSON 解析失败: " + e.getMessage());
                }
                samples.add(row);
            }
        } catch (IOException e) {
            throw new ApiException(500, "读取数据集失败: " + e.getMessage());
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("samples", samples);
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

    // ---------------- 任务控制 ----------------

    /** 启动评测（异步），同一时间仅允许一个任务 */
    public synchronized Map<String, Object> start(String dataset, List<String> graderNames, String userId) {
        Task t = current.get();
        if (t != null && "running".equals(t.status)) {
            throw new ApiException(409, "已有评测任务进行中，请等待完成");
        }
        requireDataset(dataset);
        List<String> names = graderNames == null ? List.of() : graderNames.stream().filter(n -> n != null && !n.isBlank()).toList();
        if (names.isEmpty()) {
            names = List.copyOf(graders.keySet());
        } else {
            for (String n : names) {
                if (!graders.containsKey(n)) {
                    throw new ApiException(400, "未知评测器: " + n + "（可用：" + graders.keySet() + "）");
                }
            }
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
        Task task = new Task(id, dataset, names, userId);
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
                task.detail = "评测 " + (i + 1) + "/" + total + "：" + brief(sample.query());
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", sample.id());
                row.put("query", sample.query());
                row.put("referenceResponse", sample.referenceResponse());
                row.put("tolerance", sample.tolerance());
                try {
                    // 预置 response 纯判分模式（对齐 OpenJudge）：跳过 agent，占位 record 仅承载回答
                    AgentRecord record;
                    if (sample.hasPresetResponse()) {
                        record = new AgentRecord(sample.response(), List.of(), 0, 0, 0, 0, null);
                        row.put("responseSource", "preset");
                    } else {
                        record = askAgent(sample.query(), task);
                        row.put("responseSource", "agent");
                    }
                    totalDuration += record.durationMs();
                    totalPromptTokens += record.promptTokens();
                    totalCompletionTokens += record.completionTokens();
                    row.put("answer", record.answer());
                    row.put("toolCalls", record.toolCalls());
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
     * 评测器选择 = 任务级勾选（task.graderNames，空已归一化为全集）∩ 样本指定
     * （sample.graders 为空视为不限制）；交集为空时该样本不判分（scores 为空列表）。
     * mapper 落点（对齐 OpenJudge grader_configs.mapper，数据集字段 → grader 参数名）：
     * query→query、reference_response→reference_response、tolerance→tolerance、
     * rubric→rubric 为恒等映射；response 由运行时注入（预置样本取数据集 response，
     * agent 样本取 record.answer）。
     */
    private List<Map<String, Object>> judge(EvalSample sample, AgentRecord record, Task task) {
        List<String> names;
        if (sample.graders() == null || sample.graders().isEmpty()) {
            names = task.graderNames;
        } else {
            names = sample.graders().stream().filter(task.graderNames::contains).toList();
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("query", sample.query());
        params.put("response", record.answer());
        params.put("reference_response", sample.referenceResponse());
        params.put("tolerance", sample.tolerance());
        params.put("rubric", sample.rubric());
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

    /** 汇总：passRate 按样本级均分 ≥ 0.8 记通过；errorCount 含 agent 调用失败与评测器失败 */
    private Map<String, Object> summarize(List<Map<String, Object>> sampleResults,
            long totalDuration, long totalPromptTokens, long totalCompletionTokens) {
        int total = sampleResults.size();
        int pass = 0;
        int errorSamples = 0;
        double scoreSum = 0;
        int scoreCount = 0;
        for (Map<String, Object> row : sampleResults) {
            List<Map<String, Object>> scores = (List<Map<String, Object>>) row.get("scores");
            double rowSum = 0;
            int rowCount = 0;
            boolean rowError = false;
            for (Map<String, Object> s : scores) {
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
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", total);
        m.put("passCount", pass);
        m.put("passRate", total == 0 ? 0 : Math.round(pass * 1000.0 / total) / 1000.0);
        m.put("avgScore", scoreCount == 0 ? 0 : Math.round(scoreSum / scoreCount * 1000.0) / 1000.0);
        m.put("errorCount", errorSamples);
        m.put("avgDurationMs", total == 0 ? 0 : totalDuration / total);
        m.put("promptTokens", totalPromptTokens);
        m.put("completionTokens", totalCompletionTokens);
        return m;
    }

    // ---------------- agent 驱动与轨迹采集 ----------------

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
                    List<String> gs = new ArrayList<>();
                    JsonNode gn = node.path("graders");
                    if (gn.isArray()) {
                        gn.forEach(n -> gs.add(n.asText()));
                    }
                    JsonNode ref = node.path("reference_response");
                    Object refObj = ref.isMissingNode() || ref.isNull()
                            ? null : mapper.convertValue(ref, Object.class);
                    list.add(new EvalSample(
                            node.path("id").asText("line-" + lineNo),
                            node.path("query").asText(""),
                            node.path("response").asText(null),
                            refObj,
                            node.path("tolerance").isNumber() ? node.path("tolerance").asDouble() : null,
                            node.path("rubric").asText(null),
                            gs));
                } catch (Exception e) {
                    list.add(new EvalSample("line-" + lineNo,
                            "[数据行解析失败] " + e.getMessage(), null, null, null, null, List.of()));
                }
            }
        }
        return list;
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
