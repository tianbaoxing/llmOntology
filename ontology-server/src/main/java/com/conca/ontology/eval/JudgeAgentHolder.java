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
 * judge 专用 HarnessAgent 持有者（评测二期重构：JudgeClient 由裸 HTTP 切换为 agent 通道）：
 * 纯 LLM 判分管道——禁用全部执行能力（shell/文件/记忆/技能/子代理/工具配置），
 * 仅保留模型调用与会话持久化，使 judge 调用获得与主 agent 一致的监控基建：
 * 会话留痕落 MySQL（agent_state_judge 表，可回查每次判分的 prompt 与回复）+ AgentTraceMiddleware 日志。
 *
 * 刻意不注册为 Spring bean：避免与主问答 agent（AgentScopeConfig#harnessAgent）
 * 形成 HarnessAgent 双候选，注入主流程的 ObjectProvider&lt;HarnessAgent&gt; 一行不改。
 * key 未配置时持有 null（判分前置校验由 JudgeClient.isConfigured 承担）。
 */
@Component
public class JudgeAgentHolder {

    private static final Logger log = LoggerFactory.getLogger(JudgeAgentHolder.class);

    @Value("${app.eval.judge.base-url:${app.agent.base-url:}}")
    private String baseUrl;

    @Value("${app.eval.judge.api-key:${app.agent.api-key:}}")
    private String apiKey;

    @Value("${app.eval.judge.model:${app.agent.model:}}")
    private String model;

    @Value("${app.eval.judge.max-output-tokens:400}")
    private int maxOutputTokens;

    @Value("${app.eval.judge.timeout-seconds:60}")
    private int timeoutSeconds;

    @Value("${app.eval.judge.workspace-dir:data/eval/judge-workspace}")
    private String workspaceDir;

    @org.springframework.beans.factory.annotation.Autowired
    private javax.sql.DataSource dataSource;

    @Value("${app.title:图谱}")
    private String title;

    private volatile HarnessAgent agent;

    /** 判分 agent（未配置 key 或装配失败时为 null，调用方降级报错） */
    public HarnessAgent agent() {
        return agent;
    }

    @PostConstruct
    void init() {
        if (apiKey == null || apiKey.isBlank()) {
            log.info("judge agent 未装配（缺少 API key，LLM 判分不可用）");
            return;
        }
        try {
            // 判分无需流式（整段返回解析 JSON），temperature=0 保证稳定
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

            agent = HarnessAgent.builder()
                    .name("eval-judge")
                    .description("评测判分专用（纯 LLM 管道，无工具/技能/子代理）")
                    .sysPrompt("你是问答系统质量评测专家，评测「" + title + "」问答系统的回答质量。\n"
                            + "评分采用 1-5 整数分制，具体评分标准见用户消息开头的【评分标准】区块。\n"
                            + "只输出一个 JSON 对象：{\"score\": 分数, \"reason\": \"一句话中文理由\"}，"
                            + "禁止输出 JSON 以外的任何内容。")
                    .model(chatModel)
                    .workspace(Path.of(workspaceDir))
                    .stateStore(MySqlAgentStateStores.create(dataSource, MySqlAgentStateStores.JUDGE))
                    .maxIters(1)
                    .checkRunning(false)
                    // 模型调用超时与重试：重试语义由 JudgeClient 显式控制（3s 退避重试 1 次），
                    // 框架层不叠加隐式重试
                    .modelExecutionConfig(ExecutionConfig.builder()
                            .timeout(Duration.ofSeconds(timeoutSeconds))
                            .maxAttempts(1)
                            .build())
                    // LLM 调用日志监控（AgentTraceMiddleware，slf4j 输出调用轨迹）
                    .enableAgentTracingLog(true)
                    // 最小开放面：判分是纯 LLM 任务，禁用全部执行能力
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
            log.info("judge agent 已装配（model={}，判分会话留痕表 agent_state_judge）", model);
        } catch (Exception e) {
            agent = null;
            log.warn("judge agent 装配失败，LLM 判分将不可用：{}", e.getMessage(), e);
        }
    }

    @PreDestroy
    void shutdown() {
        if (agent != null) {
            try {
                agent.close();
            } catch (Exception e) {
                log.warn("judge agent 关闭异常：{}", e.getMessage());
            }
        }
    }
}
