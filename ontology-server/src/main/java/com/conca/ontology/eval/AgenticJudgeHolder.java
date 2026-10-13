package com.conca.ontology.eval;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import com.conca.ontology.shared.db.MySqlAgentStateStores;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.harness.agent.HarnessAgent;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * Agentic 取证评测专用 HarnessAgent 持有者（三期模块一，对齐设计方案 6.1）：
 * 与 JudgeAgentHolder（纯 LLM 判分管道）的本质区别——本 agent 带只读取证工具
 * （EvalProxyTool，/api/llm/ 平面），自主决定查什么、查几次，拿到图谱证据后判分，
 * 能发现「回答正确但查证路径不存在」的深层问题。
 *
 * 装配要点：
 * - 仅注册 EvalProxyTool 一个只读工具，禁用其余全部执行能力（shell/文件/记忆/
 *   技能/子代理），maxIters=5（取证无需长链路）；
 * - 无技能加载——端点速查直接内置 sysPrompt（评测 agent 不依赖 skills 目录）；
 * - 会话留痕落 MySQL（agent_state_agentic_judge 表，userId=eval-agentic），AgentTraceMiddleware 输出轨迹日志；
 * - 刻意不注册为 Spring bean：避免与主问答 agent 形成 HarnessAgent 双候选。
 * key 未配置时 agent 为 null（grader 前置校验 + EvalService 409 提前拦截）。
 */
@Component
public class AgenticJudgeHolder {

    private static final Logger log = LoggerFactory.getLogger(AgenticJudgeHolder.class);

    @Value("${app.eval.agent.base-url:${app.agent.base-url:}}")
    private String baseUrl;

    @Value("${app.eval.agent.api-key:${app.agent.api-key:}}")
    private String apiKey;

    @Value("${app.eval.agent.model:${app.agent.model:}}")
    private String model;

    @Value("${app.eval.agent.max-iters:5}")
    private int maxIters;

    @Value("${app.eval.agent.max-queries:8}")
    private int maxQueries;

    @Value("${app.eval.agent.max-output-tokens:1200}")
    private int maxOutputTokens;

    @Value("${app.eval.agent.timeout-seconds:60}")
    private int timeoutSeconds;

    @Value("${app.eval.agent.workspace-dir:data/eval/agentic-workspace}")
    private String workspaceDir;

    @org.springframework.beans.factory.annotation.Autowired
    private javax.sql.DataSource dataSource;

    @Value("${app.agent.ontology-base-url:${ONTOLOGY_BASE_URL:}}")
    private String ontologyBaseUrl;

    @Value("${server.port:9876}")
    private int serverPort;

    private volatile HarnessAgent agent;
    private volatile EvalProxyTool tool;

    /** 取证评测 agent（未配置 key 或装配失败时为 null，调用方降级报错） */
    public HarnessAgent agent() {
        return agent;
    }

    /** 取证工具实例（与 agent 内注册的是同一对象：判分前 reset，判分后读计数与记录） */
    public EvalProxyTool tool() {
        return tool;
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank()
                && baseUrl != null && !baseUrl.isBlank()
                && model != null && !model.isBlank();
    }

    public int maxQueries() {
        return maxQueries;
    }

    /** 判分模型名（结果 metadata.judge.model 留痕） */
    public String model() {
        return model;
    }

    @PostConstruct
    void init() {
        if (!isConfigured()) {
            log.info("agentic judge agent 未装配（缺少 API key，Agentic 取证评测不可用）");
            return;
        }
        try {
            // 非流式整段返回解析 JSON；temperature=0 保证判分稳定
            OpenAIChatModel chatModel = OpenAIChatModel.builder()
                    .baseUrl(baseUrl)
                    .apiKey(apiKey)
                    .modelName(model)
                    .stream(false)
                    .generateOptions(GenerateOptions.builder()
                            .temperature(0.0)
                            .maxTokens(maxOutputTokens)
                            .build())
                    .build();

            Files.createDirectories(Path.of(workspaceDir));

            String llmBaseUrl = (ontologyBaseUrl == null || ontologyBaseUrl.isBlank())
                    ? "http://127.0.0.1:" + serverPort
                    : ontologyBaseUrl.trim().replaceAll("/+$", "");
            tool = new EvalProxyTool(llmBaseUrl, maxQueries);

            io.agentscope.core.tool.Toolkit toolkit = new io.agentscope.core.tool.Toolkit();
            toolkit.registerTool(tool);

            agent = HarnessAgent.builder()
                    .name("eval-agentic-judge")
                    .description("Agentic 取证评测（只读图谱工具，自主查证断言）")
                    .sysPrompt(sysPrompt())
                    .model(chatModel)
                    .workspace(Path.of(workspaceDir))
                    .stateStore(MySqlAgentStateStores.create(dataSource, MySqlAgentStateStores.AGENTIC_JUDGE))
                    .maxIters(maxIters)
                    .checkRunning(false)
                    .toolkit(toolkit)
                    // 模型层超时单次调用口径；整体重试由调用方显式控制
                    .modelExecutionConfig(ExecutionConfig.builder()
                            .timeout(Duration.ofSeconds(timeoutSeconds))
                            .maxAttempts(1)
                            .build())
                    .enableAgentTracingLog(true)
                    // 最小开放面：取证只需只读 HTTP 工具，禁用其余全部执行能力
                    .disableShellTool()
                    .disableFilesystemTools()
                    .disableMemoryTools()
                    .disableMemoryHooks()
                    .disableSubagents()
                    .disableDynamicSubagents()
                    .disableDynamicSkills()
                    .disableDefaultWorkspaceSkills()
                    .disableToolsConfig()
                    .disableCompaction()
                    .disableToolResultEviction()
                    .disableWorkspaceContext()
                    .disableAtPathExpansion()
                    .build();
            log.info("agentic judge agent 已装配（model={}，取证上限 {} 次，留痕表 agent_state_agentic_judge）",
                    model, maxQueries);
        } catch (Exception e) {
            agent = null;
            log.warn("agentic judge agent 装配失败，Agentic 取证评测将不可用：{}", e.getMessage(), e);
        }
    }

    /** 评测 agent 无技能加载，端点速查与输出契约直接内置 sysPrompt */
    private String sysPrompt() {
        return "你是图谱问答质量评测专家，负责对问答系统的回答做事实核查（Agentic 取证评测）。\n\n"
                + "工作流程：\n"
                + "1. 将待核查回答拆解为独立的事实断言（朝代、窑口、材质、数量、出土地、关联人物等）；\n"
                + "2. 对每条断言，用 http_request_ontology_query 工具查询图谱取证；\n"
                + "3. 输出核查结论。\n\n"
                + "可用只读端点（path 必须以 /api/llm/ 开头，写语句会被服务端拒绝）：\n"
                + "- GET /api/llm/schema/classes → 概念层类清单（含 keyProp 与中文说明）\n"
                + "- GET /api/llm/graph/stats → 各类节点数与关系数\n"
                + "- GET /api/llm/graph/node/{label}/{key} → 节点详情与出入边\n"
                + "- POST /api/llm/graph/query，body={\"cypher\":\"MATCH ... RETURN ...\",\"params\":{}} → 任意只读 Cypher\n\n"
                + "数据模型提示（按属性名检索，避免空查浪费次数）：\n"
                + "- 文物 CulturalRelic：名称在 title 属性（非 name），唯一键 itemId；名称检索用 "
                + "MATCH (n:CulturalRelic) WHERE n.title CONTAINS '关键词'\n"
                + "- Era/Place/Material/Category/Theme 等概念类：名称在 name 属性\n"
                + "- 常用关系：OF_ERA（所属朝代）、OF_CATEGORY（品类）、MADE_OF（材质）、"
                + "HAS_THEME（主题）、EXCAVATED_AT（出土地）、LOCATED_IN（位于）\n\n"
                + "规则：\n"
                + "- 最多发起 " + maxQueries + " 次查询，收到次数上限提示后立即停止取证，"
                + "剩余断言计为未证实并输出最终 JSON，reason 注明「取证截断」；\n"
                + "- score = 证实断言数 / 总断言数（0-1）；无法核实的断言计为未证实；\n"
                + "- reason 必须引用证据编号（如「证据1、3 证实，证据2 未证实」）；\n"
                + "- 最终只输出一个 JSON 对象，禁止输出其他任何内容：\n"
                + "{\"score\": 0.0到1.0的小数, \"reason\": \"中文理由\", \"evidence\": "
                + "[{\"claim\": \"断言原文\", \"cypher\": \"取证用的 Cypher 或端点路径\", "
                + "\"summary\": \"图谱返回摘要\", \"confirmed\": true}]}";
    }

    @PreDestroy
    void shutdown() {
        if (agent != null) {
            try {
                agent.close();
            } catch (Exception e) {
                log.warn("agentic judge agent 关闭异常：{}", e.getMessage());
            }
        }
    }
}
