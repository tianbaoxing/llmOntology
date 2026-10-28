package com.conca.ontology.eval.service;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.conca.ontology.agent.service.AgentSessionService;
import com.conca.ontology.eval.AgenticJudgeHolder;
import com.conca.ontology.eval.Grader;
import com.conca.ontology.eval.JudgeClient;
import com.conca.ontology.eval.entity.EvalRunEntity;
import com.conca.ontology.eval.entity.EvalRunSampleEntity;
import com.conca.ontology.eval.mapper.EvalRunMapper;
import com.conca.ontology.eval.mapper.EvalRunSampleMapper;
import com.conca.ontology.eval.model.AgentRecord;
import com.conca.ontology.eval.model.EvalSample;
import com.conca.ontology.eval.model.EvalTask;
import com.conca.ontology.eval.model.GraderInput;
import com.conca.ontology.eval.model.GraderScore;
import com.conca.ontology.user.web.SessionUser;
import com.conca.ontology.shared.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

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

import jakarta.annotation.PostConstruct;

/**
 * 评测任务存储层（P6 起 DB 化：eval_run + eval_run_sample，替代 data/eval/results/*.json）。
 * 运行态任务 start 时 INSERT（status=running），进度 UPDATE，完成时样本批量 INSERT + 任务行
 * UPDATE（TransactionTemplate 单事务）；历史读路径按原结果文件语义重建 Map（NULL 的 key 省略，
 * status NULL 按 success 处理），公共签名与 404/409 文案不变。
 *
 * <p>P1 多实例改造：全局"仅一个 running"约束由 eval_run.running_flag 生成列 + uk_running 唯一索引
 * 保证（start INSERT 即抢锁，跨实例原子互斥）；进度权威源为 DB 行（内存 current 仅本实例读缓存），
 * progress/listRuns 降级路径按心跳（heartbeat_at，15 分钟阈值）分流 running/interrupted。</p>
 * @author tianbaoxing
 * @date 2026-08-26
 */
@Service
public class EvalRunService {

    private static final Logger log = LoggerFactory.getLogger(EvalRunService.class);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final long AGENT_TIMEOUT_MINUTES = 5;
    private static final Pattern RUN_ID_RE = Pattern.compile("^eval-\\d{8}-\\d{6}-[0-9a-f]{1,3}$");
    private static final int TRUNCATE = 500;

    /** 心跳判活阈值（分钟）：agent 单轮硬超时 5min + LLM/Agentic 判分余量，超时无心跳判实例失联 */
    private static final long HEARTBEAT_STALE_MINUTES = 15;

    private final ObjectProvider<HarnessAgent> agentProvider;
    private final ObjectMapper mapper;
    private final JudgeClient judgeClient;
    private final AgenticJudgeHolder agenticJudgeHolder;
    private final AgentSessionService agentSessionService;
    private final EvalGraderRegistry graderRegistry;
    private final EvalDatasetService datasetService;
    private final EvalRunMapper runMapper;
    private final EvalRunSampleMapper sampleMapper;
    private final TransactionTemplate txTemplate;

    @Value("${app.eval.pass-threshold:0.8}")
    private double passThreshold;

    @Value("${server.port:0}")
    private int serverPort;

    private final AtomicReference<EvalTask> current = new AtomicReference<>();
    /** 本实例标识（IP:端口@启动时间，随 running 行落库 owner_instance，409 文案与排查定位用） */
    private String instanceId = "unknown";
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "eval-worker");
        t.setDaemon(true);
        return t;
    });
    private volatile long lastPersistAtMs = 0L;
    private volatile int lastPersistPercent = -1;

    public EvalRunService(ObjectProvider<HarnessAgent> agentProvider,
            ObjectMapper mapper, JudgeClient judgeClient, AgenticJudgeHolder agenticJudgeHolder,
            AgentSessionService agentSessionService, EvalGraderRegistry graderRegistry,
            EvalDatasetService datasetService,
            EvalRunMapper runMapper, EvalRunSampleMapper sampleMapper, TransactionTemplate txTemplate) {
        this.agentProvider = agentProvider;
        this.mapper = mapper;
        this.judgeClient = judgeClient;
        this.agenticJudgeHolder = agenticJudgeHolder;
        this.agentSessionService = agentSessionService;
        this.graderRegistry = graderRegistry;
        this.datasetService = datasetService;
        this.runMapper = runMapper;
        this.sampleMapper = sampleMapper;
        this.txTemplate = txTemplate;
    }

    /**
     * 实例标识：IP:端口@启动时间（如 192.168.1.100:9876@20261026-143025）。
     * @PostConstruct 时 serverPort 已注入、时刻≈服务启动，用户可直接读出"哪台机器哪个进程"；
     * IP 解析失败（容器/hosts 异常）降级 local，不影响互斥正确性（互斥靠 DB 唯一索引，标识仅定位用）。
     */
    @PostConstruct
    void initInstanceId() {
        String ip;
        try {
            ip = InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e) {
            ip = "local";
        }
        instanceId = ip + ":" + serverPort + "@" + LocalDateTime.now().format(STAMP);
        log.info("eval 实例标识: {}（多实例部署时用于评测任务定位）", instanceId);
    }

    public synchronized Map<String, Object> start(String dataset, List<String> graderNames, Integer judgeRuns, String userId) {
        EvalTask t = current.get();
        if (t != null && "running".equals(t.status)) {
            throw new ApiException(409, "已有评测任务进行中，请等待完成（任务 id：" + t.id + "，当前进度：" + t.detail + "）");
        }
        // 跨实例互斥不再前置检查：INSERT 撞 uk_running（running_flag 生成列唯一索引）即 409，原子无竞态窗口
        Integer runs = null;
        if (judgeRuns != null) {
            if (judgeRuns < 1 || judgeRuns > 3) {
                throw new ApiException(400, "judgeRuns 须在 1-3 之间（多次判分取平均降噪，judge 调用成本随次数线性增长）");
            }
            if (judgeRuns > 1) {
                runs = judgeRuns;
            }
        }
        datasetService.requireDataset(dataset);
        List<String> names = graderNames == null ? List.of() : graderNames.stream().filter(n -> n != null && !n.isBlank()).toList();
        if (names.isEmpty()) {
            throw new ApiException(400, "请至少勾选一个评测器（可用：" + graderRegistry.graders().keySet() + "）");
        }
        Map<String, Grader> graders = graderRegistry.graders();
        for (String n : names) {
            if (!graders.containsKey(n)) {
                throw new ApiException(400, "未知评测器: " + n + "（可用：" + graders.keySet() + "）");
            }
        }
        if (names.stream().anyMatch(n -> "llm".equals(graders.get(n).type())) && !judgeClient.isConfigured()) {
            throw new ApiException(409, "AI 服务未配置（缺少 OPENROUTER_API_KEY）：所选评测器包含 LLM 判分，"
                    + "请配置后重启服务或仅勾选规则评测器");
        }
        if (names.stream().anyMatch(n -> "agentic".equals(graders.get(n).type()))
                && (!agenticJudgeHolder.isConfigured() || agenticJudgeHolder.agent() == null)) {
            throw new ApiException(409, "AI 服务未配置（缺少 OPENROUTER_API_KEY）：所选评测器包含 Agentic 取证判分，"
                    + "请配置后重启服务或仅勾选规则评测器");
        }
        boolean needAgent;
        try {
            needAgent = datasetService.loadSamples(dataset).stream().anyMatch(s -> !s.hasPresetResponse());
        } catch (IOException e) {
            throw new ApiException(500, "读取数据集失败: " + e.getMessage());
        }
        if (needAgent && agentProvider.getIfAvailable() == null) {
            throw new ApiException(409, "AI 服务未配置（缺少 OPENROUTER_API_KEY）：数据集含需 agent 作答的样本，"
                    + "请配置后重启服务或改用全部预置 response 的数据集");
        }
        String id = "eval-" + LocalDateTime.now().format(STAMP) + "-"
                + Integer.toHexString((int) (Math.random() * 0xfff));
        EvalTask task = new EvalTask(id, dataset, names, runs, userId);
        lastPersistAtMs = 0L;
        lastPersistPercent = -1;
        insertRunRow(task);
        current.set(task);
        try {
            executor.submit(() -> runEval(task));
        } catch (RuntimeException e) {
            task.status = "failed";
            task.error = "executor 提交失败：" + e.getMessage();
            writeTaskMeta(task);
            current.set(null);
            throw new ApiException(500, "启动评测线程失败：" + e.getMessage());
        }
        log.info("eval started: {} dataset={} graders={}", task.id, dataset, names);
        return Map.of("taskId", task.id);
    }

    public Map<String, Object> progress(String taskId) {
        EvalTask t = current.get();
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
        // P1 跨实例 running：DB 行即权威进度（每秒节流落库，延迟 ≤1s），心跳分流 running/interrupted，
        // 不读样本表（运行中样本未落库，原降级写死 percent=100 的假完成已修复）
        if (taskId != null && RUN_ID_RE.matcher(taskId).matches()) {
            EvalRunEntity row = runMapper.selectById(taskId);
            if (row != null && "running".equals(row.getStatus())) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("taskId", taskId);
                if (isHeartbeatFresh(row)) {
                    m.put("status", "running");
                    m.put("detail", row.getDetail() == null ? "准备中" : row.getDetail());
                    m.put("percent", row.getPercent() == null ? 0 : row.getPercent());
                    m.put("error", null);
                } else {
                    m.put("status", "interrupted");
                    m.put("detail", "实例" + (row.getOwnerInstance() == null ? "" : " " + row.getOwnerInstance())
                            + " 心跳超时，任务中断");
                    m.put("percent", row.getPercent() == null ? 0 : row.getPercent());
                    m.put("error", "评测实例失联，任务中断");
                }
                return m;
            }
        }
        Map<String, Object> saved = readResult(taskId);
        if (saved == null) {
            throw new ApiException(404, "task not found: " + taskId);
        }
        boolean awaiting = "awaiting_human".equals(saved.get("status"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("taskId", taskId);
        m.put("status", saved.get("status") == null ? "success" : saved.get("status"));
        m.put("detail", awaiting ? "人工评分待完成（从结果文件读取）" : "历史任务（从结果文件读取）");
        m.put("percent", 100);
        m.put("error", null);
        m.put("resultFile", taskId + ".json");
        m.put("result", saved);
        return m;
    }

    public Map<String, Object> listRuns() {
        List<Map<String, Object>> runs = new ArrayList<>();
        EvalTask running = current.get();
        String runningId = (running != null && "running".equals(running.status)) ? running.id : null;
        for (EvalRunEntity e : runMapper.selectList(
                new LambdaQueryWrapper<EvalRunEntity>().orderByDesc(EvalRunEntity::getRunId))) {
            String id = e.getRunId();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("dataset", e.getDataset());
            m.put("finishedAt", e.getFinishedAt());
            m.put("startedAt", e.getStartedAt());
            String st = e.getStatus() == null ? "success" : e.getStatus();
            if (id.equals(runningId)) {
                // 本实例运行中（内存实时进度）
                m.put("status", st);
                m.put("_live", true);
                m.put("detail", running.detail);
                m.put("percent", running.percent);
                m.put("graders", running.graderNames);
            } else if ("running".equals(st) && isHeartbeatFresh(e)) {
                // P1 跨实例运行中（他实例任务，DB 节流进度）；原逻辑此处误标 _zombie 已修复
                m.put("status", "running");
                m.put("_remote", true);
                m.put("detail", e.getDetail());
                m.put("percent", e.getPercent());
                m.put("graders", e.getGraders());
            } else if ("running".equals(st)) {
                // running 但心跳超时：实例崩溃/失联遗留（心跳超时前最后进度）
                m.put("status", "interrupted");
                m.put("_zombie", true);
                m.put("detail", e.getDetail() == null
                        ? "实例失联，任务中断遗留" : e.getDetail());
                m.put("percent", e.getPercent());
            } else {
                m.put("status", st);
            }
            m.put("summary", toSummaryMap(e));
            if (e.getError() != null) {
                m.put("error", e.getError());
            }
            runs.add(m);
        }
        return Map.of("runs", runs);
    }

    /** 下载用：按原结果文件结构重建并序列化（保持 attachment 文件名 {taskId}.json） */
    public byte[] exportResult(String taskId) {
        Map<String, Object> result = requireResult(taskId);
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(result);
        } catch (IOException e) {
            throw new ApiException(500, "评测结果序列化失败: " + e.getMessage());
        }
    }

    public synchronized Map<String, Object> deleteRun(String taskId, SessionUser operator) {
        if (taskId == null || !RUN_ID_RE.matcher(taskId).matches()) {
            throw new ApiException(400, "非法任务 id: " + taskId);
        }
        EvalRunEntity row = runMapper.selectById(taskId);
        if (row == null) {
            throw new ApiException(404, "评测结果不存在: " + taskId);
        }
        // P1 跨实例保护：running 且心跳新鲜（或本实例在跑）的行不可删——删行即释放 uk_running
        // 全局锁且执行中实例完成后落库将 0 行回滚白跑；心跳超时的僵死行放行删除（清理路径）
        if ("running".equals(row.getStatus())) {
            EvalTask t = current.get();
            boolean localRunning = t != null && taskId.equals(t.id) && "running".equals(t.status);
            if (localRunning || isHeartbeatFresh(row)) {
                throw new ApiException(409, "评测任务正在运行中"
                        + (row.getOwnerInstance() == null ? "" : "，实例 " + row.getOwnerInstance())
                        + "，无法删除，请等待完成");
            }
        }
        log.warn("[EVAL-DELETE] 准备删除评测记录 id={} 操作者={}({})",
                taskId, operator == null ? "null" : operator.username(),
                operator == null ? "null" : operator.userId());
        txTemplate.executeWithoutResult(tx -> {
            sampleMapper.delete(new LambdaQueryWrapper<EvalRunSampleEntity>()
                    .eq(EvalRunSampleEntity::getRunId, taskId));
            runMapper.deleteById(taskId);
        });
        log.warn("[EVAL-DELETE] 删除成功 id={}", taskId);
        int remaining;
        try {
            remaining = ((List<?>) listRuns().get("runs")).size();
        } catch (Exception e) {
            remaining = -1;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", taskId);
        m.put("deletedBytes", 0);
        m.put("remaining", remaining);
        return m;
    }

    public synchronized Map<String, Object> humanScore(String taskId, String sampleId,
            Integer rawScore, String comment, Boolean skip, String userId) {
        Map<String, Object> result = requireResult(taskId);
        // P1 跨实例保护：运行中任务样本未落库（toResultMap 无 samples 键），failed 任务同样无样本明细，
        // 前置 409 替代原对 null 强转的 NPE 500
        List<Map<String, Object>> samples = (List<Map<String, Object>>) result.get("samples");
        if (samples == null) {
            throw new ApiException(409, "该任务无样本明细（运行中或已失败），无法人工评分");
        }
        if (!graderNames(result).contains("human_review")) {
            throw new ApiException(400, "该任务未启用人工评测器（human_review），无需人工评分");
        }
        boolean doSkip = Boolean.TRUE.equals(skip);
        if (!doSkip) {
            if (rawScore == null || rawScore < 1 || rawScore > 5) {
                throw new ApiException(400, "rawScore 须在 1-5 之间（1 星最低，5 星最高）");
            }
        }
        Map<String, Object> row = null;
        for (Map<String, Object> r : samples) {
            if (sampleId.equals(String.valueOf(r.get("id")))) {
                row = r;
                break;
            }
        }
        if (row == null) {
            throw new ApiException(404, "样本不存在: " + sampleId);
        }
        List<Map<String, Object>> scores = (List<Map<String, Object>>) row.get("scores");
        int idx = -1;
        for (int i = 0; i < scores.size(); i++) {
            if ("human_review".equals(scores.get(i).get("name"))) {
                idx = i;
                break;
            }
        }
        if (idx < 0) {
            throw new ApiException(400, "该样本未包含人工评测器占位分");
        }
        String text = comment == null ? "" : comment.trim();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("grader", userId);
        meta.put("scoredAt", LocalDateTime.now().toString());
        meta.put("comment", text);
        Map<String, Object> hs = new LinkedHashMap<>();
        hs.put("name", "human_review");
        hs.put("error", false);
        hs.put("pending", false);
        if (doSkip) {
            hs.put("score", null);
            hs.put("reason", "人工跳过" + (text.isEmpty() ? "" : "：" + text));
            hs.put("skipped", true);
            meta.put("skipped", true);
        } else {
            double score = (rawScore - 1) / 4.0;
            hs.put("score", score);
            hs.put("reason", "人工评分 " + rawScore + "/5" + (text.isEmpty() ? "" : "：" + text));
            meta.put("rawScore", rawScore);
        }
        hs.put("metadata", Map.of("human", meta));
        scores.set(idx, hs);

        Map<String, Object> os = result.get("summary") instanceof Map<?, ?> mm
                ? (Map<String, Object>) mm : Map.of();
        int total = (int) num(os.get("total"));
        Map<String, Object> ns = summarize(samples,
                (long) (num(os.get("avgDurationMs")) * total),
                num(os.get("promptTokens")), num(os.get("completionTokens")));
        ns.put("avgDurationMs", os.get("avgDurationMs"));
        ns.put("promptTokens", os.get("promptTokens"));
        ns.put("completionTokens", os.get("completionTokens"));
        ns.put("judge", os.get("judge"));
        result.put("summary", ns);
        String st = num(ns.get("humanPending")) == 0 ? "success" : "awaiting_human";
        result.put("status", st);
        persistHumanScore(taskId, sampleId, scores, ns, st);

        EvalTask t = current.get();
        if (t != null && t.id.equals(taskId)) {
            t.result = result;
            t.resultFile = taskId + ".json";
            t.status = st;
            t.detail = "success".equals(st) ? "人工评分完成" : "人工评分待完成";
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("taskId", taskId);
        m.put("sampleId", sampleId);
        m.put("status", st);
        m.put("humanPending", ns.get("humanPending"));
        m.put("humanScored", ns.get("humanScored"));
        m.put("humanSkipped", ns.get("humanSkipped"));
        return m;
    }

    /** 人工评分落库：样本行 scores 就地改写 + 任务行 summary/status 重算（单事务） */
    private void persistHumanScore(String taskId, String sampleId,
            List<Map<String, Object>> scores, Map<String, Object> ns, String st) {
        txTemplate.executeWithoutResult(tx -> {
            EvalRunSampleEntity srow = sampleMapper.selectOne(new LambdaQueryWrapper<EvalRunSampleEntity>()
                    .eq(EvalRunSampleEntity::getRunId, taskId)
                    .eq(EvalRunSampleEntity::getSampleId, sampleId));
            if (srow != null) {
                srow.setScores(scores);
                sampleMapper.updateById(srow);
            }
            EvalRunEntity run = new EvalRunEntity();
            run.setRunId(taskId);
            run.applySummary(ns);
            run.setStatus(st);
            runMapper.updateById(run);
        });
    }

    /**
     * start 时首写运行态任务行（status=running，summary/finished_at 各列为 NULL）。
     * P1 起 INSERT 即全局抢锁：running_flag 生成列 + uk_running 唯一索引，撞键 409（跨实例原子互斥）；
     * 其他落库异常同样阻断启动（互斥前提是锁行必须写入成功，不再 best-effort）。
     */
    private void insertRunRow(EvalTask task) {
        EvalRunEntity row = new EvalRunEntity();
        row.setRunId(task.id);
        row.setDataset(task.dataset);
        row.setGraders(task.graderNames);
        row.setJudgeRuns(task.judgeRuns);
        row.setStatus(task.status);
        row.setDetail(task.detail);
        row.setPercent(task.percent);
        row.setUserId(task.userId);
        row.setOwnerInstance(instanceId);
        row.setHeartbeatAt(LocalDateTime.now());
        row.setStartedAt(task.startedAt.toString());
        try {
            runMapper.insert(row);
        } catch (DuplicateKeyException e) {
            throw runningConflict();
        } catch (Exception e) {
            throw new ApiException(500, "评测任务启动落库失败: " + e.getMessage());
        }
    }

    /** 撞 uk_running：全局已有 running 行，按心跳分流 409 文案（别实例在跑 vs 疑似僵死可清理） */
    private ApiException runningConflict() {
        List<EvalRunEntity> holders = runMapper.selectList(new LambdaQueryWrapper<EvalRunEntity>()
                .eq(EvalRunEntity::getStatus, "running"));
        if (!holders.isEmpty()) {
            EvalRunEntity h = holders.get(0);
            String where = h.getOwnerInstance() == null ? "" : "，实例 " + h.getOwnerInstance();
            if (isHeartbeatFresh(h)) {
                return new ApiException(409, "已有评测任务进行中" + where + "（任务 id：" + h.getRunId()
                        + "，当前进度：" + (h.getPercent() == null ? 0 : h.getPercent()) + "%），请等待完成");
            }
            return new ApiException(409, "疑似僵死任务「" + h.getRunId() + "」" + where
                    + "（心跳超时，实例可能已失联），管理员可删除该记录后重试");
        }
        return new ApiException(409, "已有评测任务进行中，请稍后重试");
    }

    /** 心跳判活：running 行 heartbeat_at 距今在阈值内视为存活（实例可能仍在执行） */
    private static boolean isHeartbeatFresh(EvalRunEntity row) {
        return row.getHeartbeatAt() != null
                && row.getHeartbeatAt().isAfter(LocalDateTime.now().minusMinutes(HEARTBEAT_STALE_MINUTES));
    }

    /**
     * 进度落库：UPDATE status/detail/percent/error + 心跳（null 字段不覆盖，对齐原整文件覆写语义）。
     * P1 起条件更新 WHERE status='running'：任务行被删除或状态已变更（他实例清理）时放弃写入仅告警，
     * 防止实例恢复后把已清理的行改写回去；status 置 failed/success 的更新（失败/完成路径）同样在
     * WHERE running 匹配下执行，即任务行状态翻转即释放 uk_running 全局锁。
     */
    private void writeTaskMeta(EvalTask task) {
        try {
            EvalRunEntity row = new EvalRunEntity();
            row.setStatus(task.status);
            row.setDetail(task.detail);
            row.setPercent(task.percent);
            row.setHeartbeatAt(LocalDateTime.now());
            if (task.error != null) {
                row.setError(task.error);
            }
            int rows = runMapper.update(row, new LambdaUpdateWrapper<EvalRunEntity>()
                    .eq(EvalRunEntity::getRunId, task.id)
                    .eq(EvalRunEntity::getStatus, "running"));
            if (rows == 0) {
                log.warn("writeTaskMeta affected 0 rows id={} status={}（任务行已被删除或状态已变更）",
                        task.id, task.status);
            }
        } catch (Exception e) {
            log.warn("writeTaskMeta failed id={} status={}: {}", task.id, task.status, e.getMessage());
        }
    }

    private void persistProgress(EvalTask task, boolean force) {
        if (task == null) return;
        int p = task.percent;
        long now = System.currentTimeMillis();
        if (!force && p == lastPersistPercent && (now - lastPersistAtMs) < 1000L) {
            return;
        }
        lastPersistPercent = p;
        lastPersistAtMs = now;
        writeTaskMeta(task);
    }

    /**
     * 完成落库：样本行批量 INSERT + 任务行 UPDATE（单事务；失败抛出由 runEval 兜底置 failed）。
     * P1 起任务行 UPDATE 带 WHERE status='running'：影响 0 行（任务行已被删除或他实例清理）时
     * 抛异常回滚整事务，防止残留孤儿样本行。
     */
    private void completeRun(EvalTask task, Map<String, Object> result,
            List<Map<String, Object>> sampleResults) {
        txTemplate.executeWithoutResult(tx -> {
            for (Map<String, Object> row : sampleResults) {
                sampleMapper.insert(EvalRunSampleEntity.of(task.id, row));
            }
            EvalRunEntity run = new EvalRunEntity();
            run.setDatasetHash(result.get("datasetHash") instanceof String h ? h : null);
            run.setFinishedAt(result.get("finishedAt") instanceof String f ? f : null);
            run.setStatus(result.get("status") instanceof String s ? s : null);
            run.applySummary(result.get("summary") instanceof Map<?, ?> sm ? (Map<String, Object>) sm : null);
            int rows = runMapper.update(run, new LambdaUpdateWrapper<EvalRunEntity>()
                    .eq(EvalRunEntity::getRunId, task.id)
                    .eq(EvalRunEntity::getStatus, "running"));
            if (rows == 0) {
                throw new IllegalStateException("任务行已不存在或状态已变更，放弃完成落库: " + task.id);
            }
        });
    }

    private void runEval(EvalTask task) {
        try {
            List<EvalSample> samples = datasetService.loadSamples(task.dataset);
            String dsh;
            try {
                dsh = datasetHash(samples);
            } catch (Exception e) {
                dsh = null;
                log.warn("dataset hash failed: {}", e.getMessage());
            }
            int total = samples.size();
            task.detail = "共 " + total + " 条样本，开始评测";
            persistProgress(task, true);
            List<Map<String, Object>> sampleResults = new ArrayList<>();
            long totalDuration = 0;
            long totalPromptTokens = 0;
            long totalCompletionTokens = 0;
            Map<String, Grader> graders = graderRegistry.graders();

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
                    row.put("scores", judge(sample, record, task, graders));
                } catch (Exception e) {
                    log.warn("eval sample {} failed: {}", sample.id(), e.getMessage());
                    row.put("agentError", e.getMessage());
                    row.put("scores", List.of(GraderScore.fail("agent", "agent 调用异常：" + e.getMessage())));
                }
                sampleResults.add(row);
                task.percent = (int) (95.0 * (i + 1) / Math.max(total, 1));
                persistProgress(task, false);
            }

            task.detail = "汇总结果并落盘";
            persistProgress(task, true);
            Map<String, Object> summary = summarize(sampleResults, totalDuration,
                    totalPromptTokens, totalCompletionTokens);
            boolean hasHuman = task.graderNames.stream()
                    .anyMatch(n -> "human".equals(graders.get(n).type()));
            String status = hasHuman ? "awaiting_human" : "success";
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", task.id);
            result.put("dataset", task.dataset);
            result.put("datasetHash", dsh);
            result.put("graders", task.graderNames);
            result.put("judgeRuns", task.judgeRuns);
            result.put("startedAt", task.startedAt.toString());
            result.put("finishedAt", LocalDateTime.now().toString());
            result.put("status", status);
            result.put("summary", summary);
            result.put("samples", sampleResults);
            completeRun(task, result, sampleResults);

            task.result = result;
            task.resultFile = task.id + ".json";
            task.detail = hasHuman ? "评测完成，待人工评分" : "评测完成";
            task.percent = 100;
            task.status = status;
            log.info("eval finished: {} passRate={} avgScore={}",
                    task.id, summary.get("passRate"), summary.get("avgScore"));
            if (current.get() == task) {
                current.set(null);
            }
        } catch (Exception e) {
            task.status = "failed";
            task.error = e.getMessage();
            log.error("eval failed: {}", task.id, e);
            persistProgress(task, true);
            if (current.get() == task) {
                current.set(null);
            }
        }
    }

    private List<Map<String, Object>> judge(EvalSample sample, AgentRecord record, EvalTask task, Map<String, Grader> graders) {
        List<String> names = task.graderNames;
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("query", sample.isMultiTurn()
                ? sample.turns().get(sample.turns().size() - 1).query() : sample.query());
        params.put("response", record.answer());
        params.put("reference_response", sample.referenceResponse());
        params.put("tolerance", sample.tolerance());
        params.put("rubric", sample.rubric());
        if (task.judgeRuns != null) {
            params.put("judge_runs", task.judgeRuns);
        }
        params.put("expected_skills", sample.skills());
        params.put("expected_tools", sample.tools());
        params.put("expected_mcps", sample.mcps());
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
            if (score.metadata() != null && Boolean.TRUE.equals(score.metadata().get("pending"))) {
                m.put("pending", true);
            }
            scores.add(m);
            persistProgress(task, false); // P1：评测器间刷心跳（LLM/Agentic 判分单次可达分钟级，防同任务内心跳超时）
        }
        return scores;
    }

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
        int humanPending = 0, humanScored = 0, humanSkipped = 0;
        for (Map<String, Object> row : sampleResults) {
            List<Map<String, Object>> scores = (List<Map<String, Object>>) row.get("scores");
            double rowSum = 0;
            int rowCount = 0;
            boolean rowError = false;
            for (Map<String, Object> s : scores) {
                if ("human_review".equals(s.get("name"))) {
                    if (Boolean.TRUE.equals(s.get("pending"))) {
                        humanPending++;
                    } else if (Boolean.TRUE.equals(s.get("skipped"))) {
                        humanSkipped++;
                    } else {
                        humanScored++;
                    }
                }
                if (Boolean.TRUE.equals(s.get("pending"))) {
                    continue;
                }
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
                if (s.get("score") instanceof Number n) {
                    rowSum += n.doubleValue();
                    rowCount++;
                }
            }
            if (rowError) {
                errorSamples++;
            }
            if (rowCount > 0) {
                double avg = rowSum / rowCount;
                scoreSum += avg;
                scoreCount++;
                if (avg >= passThreshold && !rowError) {
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
        m.put("humanPending", humanPending);
        m.put("humanScored", humanScored);
        m.put("humanSkipped", humanSkipped);
        return m;
    }

    private static long num(Object v) {
        return v instanceof Number n ? n.longValue() : 0;
    }

    public Map<String, Object> diff(String baseId, String targetId, boolean all) {
        if (baseId == null || !RUN_ID_RE.matcher(baseId).matches()
                || targetId == null || !RUN_ID_RE.matcher(targetId).matches()) {
            throw new ApiException(400, "非法任务 id");
        }
        if (baseId.equals(targetId)) {
            throw new ApiException(400, "base 与 target 不能相同");
        }
        Map<String, Object> base = requireResult(baseId);
        Map<String, Object> target = requireResult(targetId);

        Map<String, Object> meta = new LinkedHashMap<>();
        Map<String, Object> baseMeta = runMeta(base);
        Map<String, Object> targetMeta = runMeta(target);
        meta.put("base", baseMeta);
        meta.put("target", targetMeta);
        String bh = (String) baseMeta.get("datasetHash");
        String th = (String) targetMeta.get("datasetHash");
        meta.put("hashMatch", bh == null || th == null ? "unknown" : bh.equals(th) ? "match" : "mismatch");
        meta.put("passThreshold", passThreshold);

        Map<String, Object> overall = new LinkedHashMap<>();
        Map<String, Object> baseMetrics = summaryMetrics(base);
        Map<String, Object> targetMetrics = summaryMetrics(target);
        overall.put("base", baseMetrics);
        overall.put("target", targetMetrics);
        Map<String, Object> delta = new LinkedHashMap<>();
        for (String k : List.of("passRate", "avgScore", "errorCount",
                "promptTokens", "completionTokens", "judgeTokens")) {
            delta.put(k, sub(targetMetrics.get(k), baseMetrics.get(k)));
        }
        overall.put("delta", delta);

        Map<String, Map<String, Object>> baseIdx = indexSamples(base);
        Map<String, Map<String, Object>> targetIdx = indexSamples(target);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("meta", meta);
        m.put("overall", overall);
        m.put("byGrader", diffByGrader(base, target, baseIdx, targetIdx));
        m.put("bySample", diffBySample(baseIdx, targetIdx, all));
        return m;
    }

    private Map<String, Object> requireResult(String taskId) {
        Map<String, Object> saved = readResult(taskId);
        if (saved == null) {
            throw new ApiException(404, "评测结果不存在: " + taskId);
        }
        return saved;
    }

    private static Map<String, Object> runMeta(Map<String, Object> run) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", run.get("id"));
        m.put("dataset", run.get("dataset"));
        m.put("datasetHash", run.get("datasetHash"));
        m.put("graders", run.get("graders"));
        m.put("finishedAt", run.get("finishedAt"));
        return m;
    }

    private static Map<String, Object> summaryMetrics(Map<String, Object> run) {
        Map<String, Object> s = run.get("summary") instanceof Map<?, ?> mm
                ? (Map<String, Object>) mm : Map.of();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("passRate", s.get("passRate"));
        m.put("avgScore", s.get("avgScore"));
        m.put("errorCount", s.get("errorCount"));
        m.put("promptTokens", s.get("promptTokens"));
        m.put("completionTokens", s.get("completionTokens"));
        long judgeTokens = 0;
        if (s.get("judge") instanceof Map<?, ?> j) {
            judgeTokens = num(j.get("promptTokens")) + num(j.get("completionTokens"));
        }
        m.put("judgeTokens", judgeTokens);
        return m;
    }

    private Map<String, Object> diffByGrader(Map<String, Object> base, Map<String, Object> target,
            Map<String, Map<String, Object>> baseIdx, Map<String, Map<String, Object>> targetIdx) {
        List<String> baseGraders = graderNames(base);
        List<String> targetGraders = graderNames(target);
        var names = new java.util.LinkedHashSet<String>(baseGraders);
        names.addAll(targetGraders);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String name : names) {
            boolean inBase = baseGraders.contains(name);
            boolean inTarget = targetGraders.contains(name);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("name", name);
            if (!inBase || !inTarget) {
                r.put("onlyIn", inBase ? "base" : "target");
                rows.add(r);
                continue;
            }
            double baseSum = 0, targetSum = 0;
            int baseCnt = 0, targetCnt = 0, passToFail = 0, failToPass = 0, common = 0;
            String baseHash = null, targetHash = null;
            for (String id : baseIdx.keySet()) {
                Map<String, Object> bs = scoresByName(baseIdx.get(id)).get(name);
                Map<String, Object> ts = targetIdx.containsKey(id)
                        ? scoresByName(targetIdx.get(id)).get(name) : null;
                if (bs == null || ts == null) {
                    continue;
                }
                common++;
                if (baseHash == null) {
                    baseHash = customPromptHash(bs);
                }
                if (targetHash == null) {
                    targetHash = customPromptHash(ts);
                }
                if (scoreVal(bs) != null) {
                    baseSum += scoreVal(bs);
                    baseCnt++;
                }
                if (scoreVal(ts) != null) {
                    targetSum += scoreVal(ts);
                    targetCnt++;
                }
                boolean bp = graderPass(bs), tp = graderPass(ts);
                if (!pending(bs) && !pending(ts)) {
                    if (bp && !tp) {
                        passToFail++;
                    } else if (!bp && tp) {
                        failToPass++;
                    }
                }
            }
            r.put("baseAvg", baseCnt == 0 ? null : round3(baseSum / baseCnt));
            r.put("targetAvg", targetCnt == 0 ? null : round3(targetSum / targetCnt));
            r.put("delta", sub(r.get("targetAvg"), r.get("baseAvg")));
            Map<String, Object> flips = new LinkedHashMap<>();
            flips.put("passToFail", passToFail);
            flips.put("failToPass", failToPass);
            r.put("flips", flips);
            r.put("commonSamples", common);
            if (baseHash != null && targetHash != null && !baseHash.equals(targetHash)) {
                r.put("customPromptChanged", true);
                r.put("note", "自定义评测器定义（提示词）已变更（" + baseHash + " → " + targetHash + "），对比仅供参考");
            }
            rows.add(r);
        }
        return Map.of("graders", rows);
    }

    private static String customPromptHash(Map<String, Object> score) {
        if (score == null || !(score.get("metadata") instanceof Map<?, ?> mm)) {
            return null;
        }
        return mm.get("custom") instanceof Map<?, ?> c && c.get("promptHash") instanceof String h ? h : null;
    }

    private Map<String, Object> diffBySample(Map<String, Map<String, Object>> baseIdx,
            Map<String, Map<String, Object>> targetIdx, boolean all) {
        List<String> added = new ArrayList<>(), removed = new ArrayList<>();
        for (String id : targetIdx.keySet()) {
            if (!baseIdx.containsKey(id)) {
                added.add(id);
            }
        }
        for (String id : baseIdx.keySet()) {
            if (!targetIdx.containsKey(id)) {
                removed.add(id);
            }
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        int changedTotal = 0;
        for (String id : baseIdx.keySet()) {
            Map<String, Object> brow = baseIdx.get(id);
            Map<String, Object> trow = targetIdx.get(id);
            if (trow == null) {
                continue;
            }
            Map<String, Object> bstats = rowStats(brow);
            Map<String, Object> tstats = rowStats(trow);
            Double baseAvg = (Double) bstats.get("avg");
            Double targetAvg = (Double) tstats.get("avg");
            boolean passChange = !bstats.get("pass").equals(tstats.get("pass"));
            boolean avgDiff = (baseAvg == null) != (targetAvg == null)
                    || (baseAvg != null && Math.abs(targetAvg - baseAvg) > 1e-9);

            Map<String, Map<String, Object>> bs = scoresByName(brow);
            Map<String, Map<String, Object>> ts = scoresByName(trow);
            List<Map<String, Object>> scores = new ArrayList<>();
            boolean anyChange = false;
            for (String name : bs.keySet()) {
                if (!ts.containsKey(name)) {
                    continue;
                }
                Map<String, Object> b = bs.get(name), t = ts.get(name);
                boolean berr = Boolean.TRUE.equals(b.get("error"));
                boolean terr = Boolean.TRUE.equals(t.get("error"));
                String flip = null;
                if (!pending(b) && !pending(t)) {
                    if (graderPass(b) && !graderPass(t)) {
                        flip = "pass_to_fail";
                    } else if (!graderPass(b) && graderPass(t)) {
                        flip = "fail_to_pass";
                    }
                }
                boolean diffScore = berr != terr
                        || (scoreVal(b) == null) != (scoreVal(t) == null)
                        || (scoreVal(b) != null && Math.abs(scoreVal(t) - scoreVal(b)) > 1e-9);
                if (diffScore || flip != null) {
                    anyChange = true;
                }
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("name", name);
                e.put("base", berr ? null : scoreVal(b));
                e.put("target", terr ? null : scoreVal(t));
                e.put("baseError", berr);
                e.put("targetError", terr);
                e.put("flip", flip);
                scores.add(e);
            }
            boolean changedRow = anyChange || passChange || avgDiff;
            if (changedRow) {
                changedTotal++;
            }
            if (!changedRow && !all) {
                continue;
            }
            Double delta = baseAvg != null && targetAvg != null ? round3(targetAvg - baseAvg) : null;
            String sampleFlip = null;
            if (Boolean.TRUE.equals(bstats.get("pass")) && !Boolean.TRUE.equals(tstats.get("pass"))) {
                sampleFlip = "pass_to_fail";
            } else if (!Boolean.TRUE.equals(bstats.get("pass")) && Boolean.TRUE.equals(tstats.get("pass"))) {
                sampleFlip = "fail_to_pass";
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", id);
            r.put("query", brow.get("query"));
            r.put("baseAvg", baseAvg == null ? null : round3(baseAvg));
            r.put("targetAvg", targetAvg == null ? null : round3(targetAvg));
            r.put("delta", delta);
            r.put("flip", sampleFlip);
            r.put("changed", changedRow);
            r.put("scores", scores);
            rows.add(r);
        }
        rows.sort(Comparator.comparingDouble(r -> r.get("delta") instanceof Number n
                ? n.doubleValue() : -1));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("commonTotal", baseIdx.size() - removed.size());
        m.put("changedTotal", changedTotal);
        m.put("all", all);
        m.put("samples", rows);
        m.put("addedIds", added);
        m.put("removedIds", removed);
        return m;
    }

    private static Map<String, Map<String, Object>> indexSamples(Map<String, Object> run) {
        Map<String, Map<String, Object>> idx = new LinkedHashMap<>();
        if (run.get("samples") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> row) {
                    idx.putIfAbsent(String.valueOf(row.get("id")), (Map<String, Object>) row);
                }
            }
        }
        return idx;
    }

    private static Map<String, Map<String, Object>> scoresByName(Map<String, Object> row) {
        Map<String, Map<String, Object>> idx = new LinkedHashMap<>();
        if (row.get("scores") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> s) {
                    idx.put(String.valueOf(s.get("name")), (Map<String, Object>) s);
                }
            }
        }
        return idx;
    }

    private Map<String, Object> rowStats(Map<String, Object> row) {
        double sum = 0;
        int cnt = 0;
        boolean err = false;
        if (row.get("scores") instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> s)) {
                    continue;
                }
                if (pending((Map<String, Object>) s)) {
                    continue;
                }
                if (Boolean.TRUE.equals(s.get("error"))) {
                    err = true;
                } else if (s.get("score") instanceof Number n) {
                    sum += n.doubleValue();
                    cnt++;
                }
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("avg", cnt == 0 ? null : sum / cnt);
        m.put("error", err);
        m.put("pass", cnt > 0 && !err && sum / cnt >= passThreshold);
        return m;
    }

    private boolean graderPass(Map<String, Object> score) {
        return score != null && !Boolean.TRUE.equals(score.get("error"))
                && score.get("score") instanceof Number n && n.doubleValue() >= passThreshold;
    }

    private static boolean pending(Map<String, Object> score) {
        return score != null && Boolean.TRUE.equals(score.get("pending"));
    }

    private static Double scoreVal(Map<String, Object> score) {
        if (pending(score)) {
            return null;
        }
        return score.get("score") instanceof Number n ? n.doubleValue() : null;
    }

    private static List<String> graderNames(Map<String, Object> run) {
        return run.get("graders") instanceof List<?> l
                ? l.stream().map(String::valueOf).toList() : List.of();
    }

    private static Object sub(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) {
            double v = x.doubleValue() - y.doubleValue();
            return Math.abs(v) < 1e-9 ? 0 : Math.round(v * 1000.0) / 1000.0;
        }
        return null;
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

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

    private AgentRecord askAgentMultiTurn(EvalSample sample, EvalTask task) {
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
            persistProgress(task, false); // P1：多轮样本每轮刷心跳（单轮 agent 硬超时 5min，防心跳超时误判失联）
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

    private AgentRecord askAgent(String query, EvalTask task) {
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
                        if (rounds[0] == 0 && e.getResult() != null && e.getResult().getUsage() != null) {
                            promptTokens[0] = e.getResult().getUsage().getInputTokens();
                            completionTokens[0] = e.getResult().getUsage().getOutputTokens();
                        }
                    }
                    default -> { }
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

    private String datasetHash(List<EvalSample> samples) {
        try {
            var writer = mapper.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
            StringBuilder sb = new StringBuilder();
            for (EvalSample s : samples) {
                sb.append(writer.writeValueAsString(s)).append('\n');
            }
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            StringBuilder hex = new StringBuilder();
            for (byte b : md.digest(sb.toString().getBytes(StandardCharsets.UTF_8))) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new ApiException(500, "数据集哈希计算失败: " + e.getMessage());
        }
    }

    private Map<String, Object> readResult(String taskId) {
        if (taskId == null || !RUN_ID_RE.matcher(taskId).matches()) {
            return null;
        }
        EvalRunEntity row = runMapper.selectById(taskId);
        if (row == null) {
            return null;
        }
        List<EvalRunSampleEntity> samples = sampleMapper.selectList(
                new LambdaQueryWrapper<EvalRunSampleEntity>()
                        .eq(EvalRunSampleEntity::getRunId, taskId)
                        .orderByAsc(EvalRunSampleEntity::getId));
        return toResultMap(row, samples);
    }

    /**
     * 按原结果文件结构重建 Map：NULL 列对应 key 省略（summary/samples 键集随文件代次自然还原，
     * status NULL 省略后由读取方按 success 处理）；键序对齐原 writeResult/writeTaskMeta 写出序。
     */
    private static Map<String, Object> toResultMap(EvalRunEntity e, List<EvalRunSampleEntity> samples) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getRunId());
        m.put("dataset", e.getDataset());
        if (e.getDatasetHash() != null) {
            m.put("datasetHash", e.getDatasetHash());
        }
        m.put("graders", e.getGraders());
        if (e.getJudgeRuns() != null) {
            m.put("judgeRuns", e.getJudgeRuns());
        }
        m.put("startedAt", e.getStartedAt());
        if (e.getFinishedAt() != null) {
            m.put("finishedAt", e.getFinishedAt());
        }
        if (e.getStatus() != null) {
            m.put("status", e.getStatus());
        }
        if (e.getDetail() != null) {
            m.put("detail", e.getDetail());
        }
        if (e.getPercent() != null) {
            m.put("percent", e.getPercent());
        }
        if (e.getError() != null) {
            m.put("error", e.getError());
        }
        if (e.getUserId() != null) {
            m.put("userId", e.getUserId());
        }
        Map<String, Object> summary = toSummaryMap(e);
        if (summary != null) {
            m.put("summary", summary);
        }
        if (e.getFinishedAt() != null || !samples.isEmpty()) {
            m.put("samples", samples.stream().map(EvalRunService::toSampleRow).toList());
        }
        return m;
    }

    /** summary 12 列全 NULL（运行态行）返回 null；否则省略 NULL 键还原原代次键集 */
    private static Map<String, Object> toSummaryMap(EvalRunEntity e) {
        if (e.getTotal() == null && e.getPassCount() == null && e.getPassRate() == null
                && e.getAvgScore() == null && e.getErrorCount() == null && e.getAvgDurationMs() == null
                && e.getPromptTokens() == null && e.getCompletionTokens() == null && e.getJudge() == null
                && e.getHumanPending() == null && e.getHumanScored() == null && e.getHumanSkipped() == null) {
            return null;
        }
        Map<String, Object> s = new LinkedHashMap<>();
        if (e.getTotal() != null) s.put("total", e.getTotal());
        if (e.getPassCount() != null) s.put("passCount", e.getPassCount());
        if (e.getPassRate() != null) s.put("passRate", e.getPassRate());
        if (e.getAvgScore() != null) s.put("avgScore", e.getAvgScore());
        if (e.getErrorCount() != null) s.put("errorCount", e.getErrorCount());
        if (e.getAvgDurationMs() != null) s.put("avgDurationMs", e.getAvgDurationMs());
        if (e.getPromptTokens() != null) s.put("promptTokens", e.getPromptTokens());
        if (e.getCompletionTokens() != null) s.put("completionTokens", e.getCompletionTokens());
        if (e.getJudge() != null) s.put("judge", e.getJudge());
        if (e.getHumanPending() != null) s.put("humanPending", e.getHumanPending());
        if (e.getHumanScored() != null) s.put("humanScored", e.getHumanScored());
        if (e.getHumanSkipped() != null) s.put("humanSkipped", e.getHumanSkipped());
        return s;
    }

    /** 样本行 Map：键序对齐 runEval 结果行写出序；NULL 列（老格式无 reference/tolerance 等）省略 */
    private static Map<String, Object> toSampleRow(EvalRunSampleEntity s) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", s.getSampleId());
        r.put("query", s.getQuery());
        if (s.getReferenceResponse() != null) {
            r.put("referenceResponse", s.getReferenceResponse());
        }
        if (s.getTolerance() != null) {
            r.put("tolerance", s.getTolerance());
        }
        if (s.getTurnCount() != null) {
            r.put("turns", s.getTurnCount());
        }
        if (s.getResponseSource() != null) {
            r.put("responseSource", s.getResponseSource());
        }
        r.put("answer", s.getAnswer());
        r.put("toolCalls", s.getToolCalls());
        if (s.getDialogue() != null) {
            r.put("dialogue", s.getDialogue());
        }
        r.put("rounds", s.getRounds());
        r.put("durationMs", s.getDurationMs());
        r.put("promptTokens", s.getPromptTokens());
        r.put("completionTokens", s.getCompletionTokens());
        r.put("agentError", s.getAgentError());
        r.put("scores", s.getScores());
        return r;
    }

    private static String brief(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 30 ? s : s.substring(0, 30) + "…";
    }
}
