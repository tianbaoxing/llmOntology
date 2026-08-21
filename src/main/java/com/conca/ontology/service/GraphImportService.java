package com.conca.ontology.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.conca.ontology.dto.BatchNodesResult;
import com.conca.ontology.dto.BatchRelsResult;
import com.conca.ontology.dto.NodeBatchRequest;
import com.conca.ontology.dto.RelBatchRequest;
import com.conca.ontology.exception.ApiException;
import com.conca.ontology.model.ClassDef;
import com.conca.ontology.model.GraphSchema;

/**
 * 批量写入封装：UNWIND + MERGE 幂等；标识符经白名单校验；数据全部参数绑定。
 * 白名单来源 GraphSchema（graph-schema.json），SchemaService CRUD 后热更新。
 */
@Service
public class GraphImportService {

    private final Driver driver;
    private final GraphSchema schema;

    @Value("${graph.batch-limit:10000}")
    private int batchLimit;

    public GraphImportService(Driver driver, GraphSchema schema) {
        this.driver = driver;
        this.schema = schema;
    }

    /** 启动时为全部注册类建唯一约束（IF NOT EXISTS，幂等） */
    public void createConstraints() {
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                for (ClassDef c : schema.allClasses()) {
                    tx.run("CREATE CONSTRAINT IF NOT EXISTS FOR (n:`" + c.name() + "`) "
                            + "REQUIRE n.`" + c.keyProp() + "` IS UNIQUE").consume();
                }
                return null;
            });
        }
    }

    /** 为单个类建唯一约束（SchemaService 新增类时调用，幂等） */
    public void createConstraint(String label, String keyProp) {
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("CREATE CONSTRAINT IF NOT EXISTS FOR (n:`" + label + "`) "
                        + "REQUIRE n.`" + keyProp + "` IS UNIQUE").consume();
                return null;
            });
        }
    }

    /** 删除类的唯一约束（SchemaService 删除类后调用） */
    public void dropConstraint(String label, String keyProp) {
        List<String> names = driver.executableQuery(
                        "SHOW CONSTRAINTS YIELD name, labelsOrTypes, properties "
                                + "WHERE labelsOrTypes = $labels AND properties = $props RETURN name")
                .withParameters(Map.of("labels", List.of(label), "props", List.of(keyProp)))
                .execute()
                .records()
                .stream().map(r -> r.get("name").asString()).toList();
        for (String name : names) {
            driver.executableQuery("DROP CONSTRAINT $name")
                    .withParameters(Map.of("name", name))
                    .execute();
        }
    }

    public BatchNodesResult batchNodes(NodeBatchRequest req) {
        String label = req.label();
        String keyProp = schema.keyPropOf(label);   // 服务端决定，忽略客户端 keyProp
        if (req.items().size() > batchLimit) {
            throw new ApiException(400, "items exceed batch-limit " + batchLimit);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> item : req.items()) {
            if (item == null || item.get(keyProp) == null || String.valueOf(item.get(keyProp)).isBlank()) {
                throw new ApiException(400, "item missing key prop `" + keyProp + "`");
            }
            rows.add(item);
        }
        String cypher = "UNWIND $rows AS row "
                + "MERGE (n:`" + label + "` {`" + keyProp + "`: row.`" + keyProp + "`}) "
                + "SET n += row";
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run(cypher, Map.of("rows", rows)).consume();
                return null;
            });
        }
        return new BatchNodesResult(label, rows.size());
    }

    public BatchRelsResult batchRels(RelBatchRequest req) {
        String type = req.type();
        String fromLabel = req.fromLabel();
        String toLabel = req.toLabel();
        schema.checkRelSignature(type, fromLabel, toLabel);   // 类型 + 方向签名校验
        String fromKey = schema.keyPropOf(fromLabel);
        String toKey = schema.keyPropOf(toLabel);
        if (req.items().size() > batchLimit) {
            throw new ApiException(400, "items exceed batch-limit " + batchLimit);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (RelBatchRequest.RelItem item : req.items()) {
            if (item == null || blank(item.from()) || blank(item.to())) {
                throw new ApiException(400, "rel item missing from/to key");
            }
            Map<String, Object> row = new HashMap<>();
            row.put("from", item.from());
            row.put("to", item.to());
            row.put("props", item.props() == null ? Map.of() : item.props());
            rows.add(row);
        }
        String cypher = "UNWIND $rows AS row "
                + "MATCH (a:`" + fromLabel + "` {`" + fromKey + "`: row.from}) "
                + "MATCH (b:`" + toLabel + "` {`" + toKey + "`: row.to}) "
                + "MERGE (a)-[r:`" + type + "`]->(b) "
                + "SET r += row.props";
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run(cypher, Map.of("rows", rows)).consume();
                return null;
            });
        }
        return new BatchRelsResult(type, rows.size());
    }

    private boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
