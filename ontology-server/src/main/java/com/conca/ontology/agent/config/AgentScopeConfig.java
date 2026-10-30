package com.conca.ontology.agent.config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.agentscope.core.studio.StudioManager;
import io.agentscope.harness.agent.HarnessAgent;

/**
 * AgentScope 2.0 Harness 装配入口。
 *
 * 装配逻辑（OpenAI 兼容协议模型 + 工具集 + 中间件 + 状态存储）统一在
 * {@link HarnessAgentFactory}，本类只负责两件事：
 * - llmBaseUrl：内置 HTTP 工具回连本服务的 base-url（主智能体与自建智能体注册表共用）；
 * - harnessAgent：启动期主智能体单例（modelName = app.agent.model；key 未配置注册为
 *   NullBean，问答接口返回明确错误）。Studio 对接只在主智能体装配后执行一次。
 *
 * @author tianbaoxing
 * @date 2026-08-20
 */
@Configuration
public class AgentScopeConfig {

    private static final Logger log = LoggerFactory.getLogger(AgentScopeConfig.class);

    /**
     * 平台内置 HTTP 工具（graph-query 管道等）回连本服务的 base-url。
     * 解析优先级：app.agent.ontology-base-url → 环境变量 ONTOLOGY_BASE_URL → 本机回环端口。
     * 主智能体与自建智能体注册表（AgentRegistry）共用，避免两处各算一份。
     */
    @Bean("llmBaseUrl")
    public String llmBaseUrl(
            @Value("${app.agent.ontology-base-url:${ONTOLOGY_BASE_URL:}}") String ontologyBaseUrl,
            @Value("${server.port:9876}") int serverPort) {
        return (ontologyBaseUrl == null || ontologyBaseUrl.isBlank())
                ? "http://127.0.0.1:" + serverPort
                : ontologyBaseUrl.trim().replaceAll("/+$", "");
    }

    @Bean(destroyMethod = "close")
    public HarnessAgent harnessAgent(
            @Value("${app.agent.api-key:}") String apiKey,
            @Value("${app.agent.model}") String model,
            @Value("${app.agent.studio-url:}") String studioUrl,
            @Value("${app.title:图谱}") String title,
            HarnessAgentFactory factory) throws Exception {

        // key 未配置时返回 null（注册为 NullBean），问答接口返回明确错误，不影响其余功能
        if (!factory.apiKeyPresent()) {
            return null;
        }

        HarnessAgent agent = factory.build(model);

        // AgentScope Studio 可视化对接（可选）：初始化成功后自动注册 Run、
        // 全局挂 StudioMessageHook 推送消息、全局注册 OTLP Tracer 上报 Trace；
        // Studio 未启动时降级告警，不阻断主服务（只在主智能体装配后执行一次）
        if (studioUrl != null && !studioUrl.isBlank()) {
            try {
                StudioManager.init()
                        .studioUrl(studioUrl.trim().replaceAll("/+$", ""))
                        .project(title)
                        .initialize()
                        .block();
                log.info("AgentScope Studio 对接成功：{}（project={}）", studioUrl, title);
                Runtime.getRuntime().addShutdownHook(new Thread(StudioManager::shutdown));
            } catch (Exception e) {
                log.warn("AgentScope Studio 连接失败（{}），本次运行不推送可视化数据：{}", studioUrl, e.getMessage());
            }
        }
        return agent;
    }
}
