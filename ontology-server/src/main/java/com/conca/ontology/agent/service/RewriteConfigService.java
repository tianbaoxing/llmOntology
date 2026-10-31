package com.conca.ontology.agent.service;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.conca.ontology.agent.entity.RewriteConfigItem;
import com.conca.ontology.agent.mapper.RewriteConfigItemMapper;
import com.conca.ontology.shared.web.ApiException;
import com.conca.ontology.agent.dto.RewriteConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.harness.agent.HarnessAgent;

/**
 * 语义改写配置与执行服务（MySQL rewrite_config 表，每功能一行，无全局 version）。
 *
 * 4 项功能（页面「语义改写配置」Tab 各一张卡片）：
 * - semantic 语义改写 / typo 错别字改写 / term 术语改写 / context 上下文改写
 * 每项配置：enabled 启用开关、prompt 提示词（用户可改，保存即热生效）。
 *
 * 执行通道统一为独立 rewrite agent（RewriteAgentHolder，每次改写独立会话留痕
 * 可回查）；传给 agent 的提示词 = 功能提示词 + 输出格式提示词，输出格式提示词
 * 是代码常量（页面只读展示，不入配置文件，改配置文件也无法影响执行）。
 *
 * 聊天接入（AgentChatService 前置编排）：多功能同时启用时按固定管道顺序链式
 * 执行（每环吃上一环 rewritten_text）：错别字 → 术语 → 上下文 → 语义（先纠错、
 * 再对齐、后结构化）；上下文改写额外携带会话最近 N 条历史，其余三项只处理当前
 * 文本。完整过程以 RewriteTrace 存入 USER 消息 metadata（历史回看时由
 * AgentSessionService 还原原句并渲染改写卡片），实时展示与历史回放共用
 * stepLine/traceToText 渲染函数（保证刷新前后形态一致）。
 *
 * 输出统一解析为 {"rewritten_text": "...", "explanation": "..."}；
 * 单项测试失败抛 ApiException（页面可见），聊天管道单项失败降级保留当前文本不阻断。
 *
 * @author tianbaoxing
 * @date 2026-09-11
 */
@Service
public class RewriteConfigService {

    private static final Logger log = LoggerFactory.getLogger(RewriteConfigService.class);

    /** 功能类型（管道执行顺序：先纠错、再对齐术语、补全上下文、最后语义结构化） */
    public static final String TYPE_SEMANTIC = "semantic";
    public static final String TYPE_TYPO = "typo";
    public static final String TYPE_TERM = "term";
    public static final String TYPE_CONTEXT = "context";
    public static final List<String> PIPELINE_ORDER = List.of(TYPE_TYPO, TYPE_TERM, TYPE_CONTEXT, TYPE_SEMANTIC);
    public static final List<String> ALL_TYPES = List.of(TYPE_SEMANTIC, TYPE_TYPO, TYPE_TERM, TYPE_CONTEXT);

    /** 管道步骤状态：改写生效 / 无需改写 / 功能未启用 / 执行失败 */
    public static final String STATUS_APPLIED = "applied";
    public static final String STATUS_UNCHANGED = "unchanged";
    public static final String STATUS_SKIPPED = "skipped";
    public static final String STATUS_ERROR = "error";

    /** USER 消息 metadata 键：改写过程 trace 的 JSON 字符串（历史还原原句/渲染改写卡片） */
    public static final String META_REWRITE_TRACE = "rewrite_trace";

    /** 页面展示名（RewriteAgentHolder 构建描述复用） */
    public static final Map<String, String> FEATURE_LABELS = Map.of(
            TYPE_SEMANTIC, "语义改写",
            TYPE_TYPO, "错别字改写",
            TYPE_TERM, "术语改写",
            TYPE_CONTEXT, "上下文改写");

    /** 默认提示词（首次落盘与页面初始值；semantic 为用户设定原文） */
    private static final Map<String, String> DEFAULT_PROMPTS = Map.of(
            TYPE_SEMANTIC, "你是一个语义转换专家。请理解用户的真实意图，并将其转换为标准查询格式。\n"
                    + "规则：\n1.识别用户的潜在需求\n2.保持语义等价性\n3.将口语化表达转为结构化查询",
            TYPE_TYPO, "你是一个文本校对专家。请识别并纠正用户输入中的错别字，"
                    + "包括同音字错误、形近字错误和拼音输入错误。\n"
                    + "规则：\n1.只纠正确定的错别字，不改变原句语义和结构\n"
                    + "2.不确定是否错误的字词保持原样\n3.不做任何语义层面的改写",
            TYPE_TERM, "你是一个术语标准化专家。请识别用户输入中的口语化表达和非标准术语，"
                    + "将其替换为标准术语。\n"
                    + "规则：\n1.只替换术语词汇，不改动句子其余部分\n"
                    + "2.无法确定标准术语时保持原样\n3.领域专有名词无对应标准术语时保持原样",
            TYPE_CONTEXT, "你是一个上下文补全专家。请结合对话历史，"
                    + "将用户输入中的省略式表达和指代式表达（如“它”“他”“上面那个”）"
                    + "改写为完整、独立、无歧义的查询。\n"
                    + "规则：\n1.仅补全指代和省略部分，不改动其余内容\n"
                    + "2.对话历史中无足够信息时输出原文\n"
                    + "3.改写后的文本必须脱离对话历史也能独立理解");

    /** 输出格式提示词（代码常量：只读、不入配置文件，拼接发生在后端） */
    private static final Map<String, String> OUTPUT_FORMATS = Map.of(
            TYPE_SEMANTIC, "严格按照以下 JSON 格式输出，不要包含任何其他文字：\n"
                    + "{\"rewritten_text\":\"语义改写后的完整文本\",\"explanation\":\"改写说明\"}\n"
                    + "如果无需改写，rewritten_text 输出原文，explanation 说明原因。",
            TYPE_TYPO, "严格按照以下 JSON 格式输出，不要包含任何其他文字：\n"
                    + "{\"rewritten_text\":\"纠正错别字后的完整文本\",\"explanation\":\"改写说明\"}\n"
                    + "如果无需改写，rewritten_text 输出原文，explanation 说明原因。",
            TYPE_TERM, "严格按照以下 JSON 格式输出，不要包含任何其他文字：\n"
                    + "{\"rewritten_text\":\"术语标准化后的完整文本\",\"explanation\":\"改写说明\"}\n"
                    + "如果无需改写，rewritten_text 输出原文，explanation 说明原因。",
            TYPE_CONTEXT, "严格按照以下 JSON 格式输出，不要包含任何其他文字：\n"
                    + "{\"rewritten_text\":\"补全上下文后的完整文本\",\"explanation\":\"改写说明\"}\n"
                    + "如果无需改写，rewritten_text 输出原文，explanation 说明原因。");

    /** trace 解析专用（静态函数供 AgentSessionService 复用，不依赖注入实例） */
    private static final ObjectMapper TRACE_MAPPER = new ObjectMapper();

    private final ObjectMapper mapper;
    private final RewriteAgentHolder agentHolder;
    private final RewriteConfigItemMapper itemMapper;

    @Value("${app.agent.api-key:}")
    private String apiKey;

    @Value("${app.rewrite.timeout-seconds:30}")
    private int timeoutSeconds;

    public RewriteConfigService(ObjectMapper mapper, RewriteAgentHolder agentHolder,
            RewriteConfigItemMapper itemMapper) {
        this.mapper = mapper;
        this.agentHolder = agentHolder;
        this.itemMapper = itemMapper;
    }

    // ---------------- 配置读写 ----------------

    /** 全量配置（缺失功能键内存兜底默认值，对齐原「文件不存在返回内置默认」语义） */
    public RewriteConfig loadAll() {
        Map<String, RewriteConfigItem> rows = new LinkedHashMap<>();
        for (RewriteConfigItem row : itemMapper.selectList(null)) {
            rows.put(row.getFeature(), row);
        }
        Map<String, RewriteConfig.Feature> features = new LinkedHashMap<>();
        for (String type : ALL_TYPES) {
            RewriteConfigItem row = rows.get(type);
            features.put(type, row != null
                    ? new RewriteConfig.Feature(row.getEnabled(), row.getPrompt())
                    : defaultFeature(type));
        }
        return new RewriteConfig(features);
    }

    /** 页面视图：配置 + 只读输出格式提示词 + 管道顺序 */
    public ConfigView view() {
        return view(loadAll());
    }

    private ConfigView view(RewriteConfig cfg) {
        Map<String, FeatureView> features = new LinkedHashMap<>();
        for (String type : ALL_TYPES) {
            RewriteConfig.Feature f = cfg.features().get(type);
            features.put(type, new FeatureView(f.enabled(), f.prompt(), OUTPUT_FORMATS.get(type)));
        }
        return new ConfigView(PIPELINE_ORDER, features);
    }

    /** 保存（部分更新：缺省功能行不动；提交项校验通过后行级 upsert，聊天管道热生效） */
    public ConfigView save(Map<String, RewriteConfig.Feature> features) {
        if (features == null || features.isEmpty()) {
            throw new ApiException(400, "features 不能为空");
        }
        for (String key : features.keySet()) {
            if (!ALL_TYPES.contains(key)) {
                throw new ApiException(400, "未知功能类型: " + key);
            }
        }
        for (String type : ALL_TYPES) {
            RewriteConfig.Feature incoming = features.get(type);
            if (incoming == null) {
                continue;
            }
            String prompt = incoming.prompt() == null ? "" : incoming.prompt().trim();
            if (prompt.isBlank()) {
                throw new ApiException(400, FEATURE_LABELS.get(type) + "：提示词不能为空");
            }
            upsert(type, incoming.enabled(), prompt);
        }
        log.info("rewrite config saved: {}", features.keySet());
        return view(loadAll());
    }

    private void upsert(String type, boolean enabled, String prompt) {
        RewriteConfigItem existing = itemMapper.selectById(type);
        RewriteConfigItem row = existing != null ? existing : new RewriteConfigItem();
        row.setFeature(type);
        row.setEnabled(enabled);
        row.setPrompt(prompt);
        row.setUpdatedAt(LocalDateTime.now());
        if (existing == null) {
            itemMapper.insert(row);
        } else {
            itemMapper.updateById(row);
        }
    }

    private static RewriteConfig.Feature defaultFeature(String type) {
        return new RewriteConfig.Feature(false, DEFAULT_PROMPTS.get(type));
    }

    // ---------------- 单项执行 ----------------

    /** 执行单项功能改写（无论是否启用；失败抛 ApiException 供页面展示） */
    public RewriteResult execute(String type, String text, List<String> history) {
        if (!ALL_TYPES.contains(type)) {
            throw new ApiException(400, "未知功能类型: " + type);
        }
        if (text == null || text.isBlank()) {
            throw new ApiException(400, "测试文本不能为空");
        }
        RewriteConfig cfg = loadAll();
        long t0 = System.currentTimeMillis();
        StepResult r = executeStep(type, cfg.features().get(type), text.trim(), history);
        return new RewriteResult(type, r.step().rewrittenText(), r.step().explanation(),
                System.currentTimeMillis() - t0);
    }

    /**
     * 执行一项改写（聊天管道单环 / 页面单项测试共用）：
     * 独立 rewrite-<type> agent 单次调用，返回改写步骤（含 token 用量）。
     * 失败抛异常（调用方决定降级或上抛）。
     */
    public StepResult executeStep(String type, RewriteConfig.Feature feature,
            String text, List<String> history) {
        if (feature == null) {
            throw new ApiException(400, "未知功能类型: " + type);
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new ApiException(503, "未配置 LLM API key（app.agent.api-key），无法执行改写");
        }
        String sysPrompt = feature.prompt() + "\n\n" + OUTPUT_FORMATS.get(type);
        String userContent = buildUserContent(text, TYPE_CONTEXT.equals(type) ? history : List.of());
        HarnessAgent agent = agentHolder.agent(type, sysPrompt);
        Msg userMsg = Msg.builder().role(MsgRole.USER).textContent(userContent).build();
        RuntimeContext rc = RuntimeContext.builder()
                .userId("rewrite-" + type)
                .sessionId(RewriteAgentHolder.nextSessionId(type))
                .build();
        Msg reply = agent.call(userMsg, rc).block(Duration.ofSeconds(timeoutSeconds));
        String content = reply == null ? null : reply.getTextContent();
        RewriteOutcome outcome = parseOutcome(content, text);
        boolean applied = !outcome.text().trim().equals(text.trim());
        ChatUsage usage = reply == null ? null : reply.getUsage();
        long in = usage == null ? 0L : usage.getInputTokens();
        long out = usage == null ? 0L : usage.getOutputTokens();
        PipelineStep step = new PipelineStep(type, applied ? STATUS_APPLIED : STATUS_UNCHANGED,
                outcome.text(), outcome.explanation(), null, in, out);
        return new StepResult(step, in, out);
    }

    // ---------------- trace 渲染（实时 SSE 与历史回放共用，保证形态一致） ----------------

    /** 单步展示行（thinking 增量与历史 thinking 字段同源） */
    public static String stepLine(PipelineStep s) {
        String label = "[" + FEATURE_LABELS.getOrDefault(s.type(), s.type()) + "] ";
        String status = s.status() == null ? "" : s.status();
        return switch (status) {
            case STATUS_SKIPPED -> label + "⊘ 未启用";
            case STATUS_APPLIED -> label + "✔ " + s.rewrittenText() + explain(s.explanation());
            case STATUS_UNCHANGED -> label + "○ 保持原文" + explain(s.explanation());
            case STATUS_ERROR -> label + "⚠ 失败：" + (s.error() == null ? "未知异常" : s.error()) + "，沿用当前文本";
            default -> label + String.valueOf(s.rewrittenText());
        };
    }

    /** 完整 trace 文本：原句 → 各步骤行 → 送入 AI 的最终文本 */
    public static String traceToText(RewriteTrace t) {
        if (t == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder("原句：").append(t.original());
        if (t.steps() != null) {
            for (PipelineStep s : t.steps()) {
                sb.append('\n').append(stepLine(s));
            }
        }
        return sb.append("\n送入 AI：").append(t.finalText()).toString();
    }

    /** trace 序列化（存 USER 消息 metadata；失败返回 null，调用方跳过 metadata） */
    public static String traceJson(RewriteTrace t) {
        try {
            return TRACE_MAPPER.writeValueAsString(t);
        } catch (Exception e) {
            log.warn("rewrite trace 序列化失败：{}", e.getMessage());
            return null;
        }
    }

    /** 从消息 metadata 解析 trace（无 / 解析失败返回 null） */
    public static RewriteTrace traceOf(Msg msg) {
        if (msg == null || msg.getMetadata() == null) {
            return null;
        }
        Object raw = msg.getMetadata().get(META_REWRITE_TRACE);
        if (raw == null) {
            return null;
        }
        try {
            if (raw instanceof String s) {
                return TRACE_MAPPER.readValue(s, RewriteTrace.class);
            }
            return TRACE_MAPPER.convertValue(raw, RewriteTrace.class);
        } catch (Exception e) {
            log.warn("rewrite trace 解析失败：{}", e.getMessage());
            return null;
        }
    }

    // ---------------- 输出解析 ----------------

    /**
     * 解析 {"rewritten_text": "...", "explanation": "..."}；
     * 容忍 markdown 围栏与前后杂文（截取首个 { 到末个 }）。
     * rewritten_text 缺失或为空时按“无需改写”处理（返回原文）。
     */
    private RewriteOutcome parseOutcome(String content, String original) {
        if (content == null || content.isBlank()) {
            throw new ApiException(502, "改写输出为空，无法解析");
        }
        int l = content.indexOf('{');
        int r = content.lastIndexOf('}');
        if (l < 0 || r <= l) {
            throw new ApiException(502, "改写输出无法解析为 JSON：" + brief(content));
        }
        try {
            JsonNode node = mapper.readTree(content.substring(l, r + 1));
            String rewritten = node.path("rewritten_text").asText("").trim();
            String explanation = node.path("explanation").asText("").trim();
            if (rewritten.isEmpty()) {
                return new RewriteOutcome(original,
                        explanation.isEmpty() ? "改写输出缺少 rewritten_text，按无需改写处理" : explanation);
            }
            return new RewriteOutcome(rewritten, explanation);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(502, "改写输出无法解析为 JSON：" + brief(content));
        }
    }

    private static String buildUserContent(String text, List<String> history) {
        StringBuilder sb = new StringBuilder();
        if (history != null && !history.isEmpty()) {
            sb.append("【对话历史】（最近几轮，仅供理解上下文）\n");
            for (String line : history) {
                sb.append(line).append('\n');
            }
            sb.append('\n');
        }
        sb.append("【待改写文本】\n").append(text);
        return sb.toString();
    }

    private static String explain(String explanation) {
        return explanation == null || explanation.isBlank() ? "" : "（" + explanation + "）";
    }

    private static String brief(String s) {
        return s.length() <= 120 ? s : s.substring(0, 120) + "…";
    }

    // ---------------- DTO ----------------

    private record RewriteOutcome(String text, String explanation) {
    }

    /** GET/PUT 接口返回：配置 + 只读输出格式提示词 */
    public record FeatureView(boolean enabled, String prompt, String outputFormat) {
    }

    public record ConfigView(List<String> pipeline, Map<String, FeatureView> features) {
    }

    /** 测试接口返回 */
    public record RewriteResult(String type, String rewrittenText, String explanation, long durationMs) {
    }

    /** 管道单步结果（含 token 用量，供聊天改写轮统计与历史卡片展示） */
    public record StepResult(PipelineStep step, long promptTokens, long completionTokens) {
    }

    /**
     * 管道单步（trace 持久化单元）：status 见 STATUS_* 常量；
     * skipped/error 步骤 rewrittenText 为 null。
     */
    public record PipelineStep(String type, String status, String rewrittenText,
            String explanation, String error, long promptTokens, long completionTokens) {
    }

    /** 一次聊天改写管道的完整过程（存 USER 消息 metadata，历史回放数据源） */
    public record RewriteTrace(String original, String finalText, List<PipelineStep> steps) {
    }
}
