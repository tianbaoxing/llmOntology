package {{PACKAGE_NAME}};

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import {{PACKAGE_NAME}}.config.AgentProperties;

/**
 * {{PROJECT_NAME}} —— 由 llmOntology 平台导出的 AgentScope 2.0 智能体工程。
 * 模型密钥经环境变量 AGENT_API_KEY 注入（application.yml: agent.model.api-key 占位符），
 * 启动后访问 http://127.0.0.1:18080 对话；运行与二次开发指引见 README.md。
 */
@SpringBootApplication
@EnableConfigurationProperties(AgentProperties.class)
public class AgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentApplication.class, args);
    }
}
