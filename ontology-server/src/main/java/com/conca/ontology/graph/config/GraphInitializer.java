package com.conca.ontology.graph.config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import com.conca.ontology.graph.model.GraphSchema;
import com.conca.ontology.graph.service.GraphImportService;
import com.conca.ontology.graph.service.SchemaService;

/**
 * 启动初始化：加载 schema 文件（不存在则初始化为空 schema）
 * → 等待 Neo4j 可用后按注册表建唯一约束（重试 10 次 × 3s）。
 */
@Component
public class GraphInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(GraphInitializer.class);

    private final GraphImportService importService;
    private final SchemaService schemaService;
    private final GraphSchema schema;

    public GraphInitializer(GraphImportService importService, SchemaService schemaService, GraphSchema schema) {
        this.importService = importService;
        this.schemaService = schemaService;
        this.schema = schema;
    }

    @Override
    public void run(ApplicationArguments args) {
        schemaService.init();
        for (int i = 1; i <= 10; i++) {
            try {
                importService.createConstraints();
                log.info("neo4j constraints ready ({} classes)", schema.allClasses().size());
                return;
            } catch (Exception e) {
                log.warn("neo4j not ready ({}/10): {}", i, e.getMessage());
                if (i == 10) {
                    throw new IllegalStateException("neo4j unreachable after retries", e);
                }
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted while waiting neo4j", ie);
                }
            }
        }
    }
}
