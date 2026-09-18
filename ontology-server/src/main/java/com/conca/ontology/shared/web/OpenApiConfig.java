package com.conca.ontology.shared.web;

import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;

/**
 * OpenAPI 文档配置：分组隔离 LLM 平面与管理端。
 * - llm 组：/api/llm/**（LLM 调用方专属，免登录）
 * - admin 组：/api/** 管理端接口（需登录，文档仅描述接口不执行）
 * 访问 /swagger-ui.html 切换分组；/v3/api-docs/{group} 输出 OpenAPI JSON（可直接注入 LLM 上下文）。
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI ontologyOpenApi() {
        return new OpenAPI().info(new Info()
                .title("llmOntology API")
                .description("Neo4j 本体图谱服务。llm 组 = LLM 调用方专属平面（免登录）；"
                        + "admin 组 = 管理页面接口（Session 认证）。所有接口统一响应信封 {code, msg, data}，"
                        + "code=0 成功，非 0 为 HTTP 状态码语义。")
                .version("0.1.0"));
    }

    @Bean
    public GroupedOpenApi llmGroup() {
        return GroupedOpenApi.builder()
                .group("llm")
                .pathsToMatch("/api/llm/**")
                .packagesToScan("com.conca.ontology.graph.llmapi", "com.conca.ontology.file.llmapi", "com.conca.ontology.disjoint.llmapi")
                .build();
    }

    @Bean
    public GroupedOpenApi adminGroup() {
        return GroupedOpenApi.builder()
                .group("admin")
                .pathsToMatch("/api/**")
                .packagesToScan("com.conca.ontology.user.api", "com.conca.ontology.graph.api", "com.conca.ontology.file.api", "com.conca.ontology.agent.api", "com.conca.ontology.content.api", "com.conca.ontology.eval.api", "com.conca.ontology.skill.api", "com.conca.ontology.backup.api", "com.conca.ontology.disjoint.api")
                .build();
    }
}
