package com.conca.ontology.service;

import java.util.LinkedHashMap;
import java.util.Map;

import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.springframework.stereotype.Service;

import com.conca.ontology.dto.result.NodeCreateResult;
import com.conca.ontology.dto.result.NodeDeleteResult;
import com.conca.ontology.dto.result.NodeUpdateResult;
import com.conca.ontology.dto.result.RelCreateResult;
import com.conca.ontology.dto.result.RelDeleteResult;
import com.conca.ontology.dto.request.RelRequest;
import com.conca.ontology.dto.result.RelUpdateResult;
import com.conca.ontology.shared.web.ApiException;
import com.conca.ontology.model.GraphSchema;

/**
 * 图数据单条 CRUD：节点（MERGE 幂等 / SET += / DETACH DELETE）、
 * 关系（type + 两端键定位，MERGE / SET += / DELETE）。
 * 标识符经 GraphSchema 白名单校验后拼接，数据全参数绑定。
 */
@Service
public class GraphWriteService {

    private final Driver driver;
    private final GraphSchema schema;

    public GraphWriteService(Driver driver, GraphSchema schema) {
        this.driver = driver;
        this.schema = schema;
    }

    // ---------------- node ----------------

    public NodeCreateResult createNode(String label, Map<String, Object> props) {
        String keyProp = schema.keyPropOf(label);
        Object key = props.get(keyProp);
        if (key == null || String.valueOf(key).isBlank()) {
            throw new ApiException(400, "props missing key prop `" + keyProp + "`");
        }
        boolean existed = nodeCount(label, keyProp, key) > 0;
        write("MERGE (n:`" + label + "` {`" + keyProp + "`: $key}) SET n += $props",
                Map.of("key", key, "props", props));
        return new NodeCreateResult(label, keyProp, key, existed);
    }

    public NodeUpdateResult updateNode(String label, String key, Map<String, Object> props) {
        String keyProp = schema.keyPropOf(label);
        Map<String, Object> setProps = new LinkedHashMap<>(props);
        setProps.remove(keyProp);
        if (setProps.isEmpty()) {
            throw new ApiException(400, "no updatable props (keyProp `" + keyProp + "` is immutable)");
        }
        long c = writeCount("MATCH (n:`" + label + "` {`" + keyProp + "`: $key}) SET n += $props RETURN count(n) AS c",
                Map.of("key", key, "props", setProps));
        if (c == 0) {
            throw new ApiException(404, "node not found: " + label + "/" + key);
        }
        return new NodeUpdateResult(label, key, setProps.size());
    }

    public NodeDeleteResult deleteNode(String label, String key, boolean detach) {
        String keyProp = schema.keyPropOf(label);
        if (!detach) {
            long rels = scalar("MATCH (n:`" + label + "` {`" + keyProp + "`: $key})-[r]-() RETURN count(r) AS c",
                    Map.of("key", key));
            if (rels > 0) {
                throw new ApiException(409, "node has " + rels + " relationships; use detach=true");
            }
        }
        String cypher = "MATCH (n:`" + label + "` {`" + keyProp + "`: $key}) "
                + (detach ? "DETACH DELETE n" : "DELETE n") + " RETURN count(n) AS c";
        long c = writeCount(cypher, Map.of("key", key));
        if (c == 0) {
            throw new ApiException(404, "node not found: " + label + "/" + key);
        }
        return new NodeDeleteResult(label, key, true, detach);
    }

    // ---------------- relationship ----------------

    public RelCreateResult createRel(RelRequest req) {
        schema.checkRelSignature(req.type(), req.fromLabel(), req.toLabel());
        if (nodeCount(req.fromLabel(), schema.keyPropOf(req.fromLabel()), req.fromKey()) == 0) {
            throw new ApiException(404, "from node not found: " + req.fromLabel() + "/" + req.fromKey());
        }
        if (nodeCount(req.toLabel(), schema.keyPropOf(req.toLabel()), req.toKey()) == 0) {
            throw new ApiException(404, "to node not found: " + req.toLabel() + "/" + req.toKey());
        }
        boolean existed = relCount(req) > 0;
        write(relEndpoints(req) + "MERGE (a)-[r:`" + req.type() + "`]->(b) SET r += $props", relParams(req));
        return new RelCreateResult(req.type(), req.fromKey(), req.toKey(), existed);
    }

    public RelUpdateResult updateRel(RelRequest req) {
        schema.checkRelSignature(req.type(), req.fromLabel(), req.toLabel());
        Map<String, Object> props = req.props();
        if (props == null || props.isEmpty()) {
            throw new ApiException(400, "props must not be empty");
        }
        long c = writeCount(relCypher(req, "SET r += $props RETURN count(r) AS c"), relParams(req));
        if (c == 0) {
            throw new ApiException(404, relNotFoundMsg(req));
        }
        return new RelUpdateResult(req.type(), req.fromKey(), req.toKey(), props.size());
    }

    public RelDeleteResult deleteRel(RelRequest req) {
        schema.checkRelSignature(req.type(), req.fromLabel(), req.toLabel());
        long c = writeCount(relCypher(req, "DELETE r RETURN count(r) AS c"), relParams(req));
        if (c == 0) {
            throw new ApiException(404, relNotFoundMsg(req));
        }
        return new RelDeleteResult(req.type(), req.fromKey(), req.toKey(), true);
    }

    // ---------------- helpers ----------------

    private String relNotFoundMsg(RelRequest req) {
        return "relationship not found: " + req.type() + " ("
                + req.fromLabel() + "/" + req.fromKey() + ")->(" + req.toLabel() + "/" + req.toKey() + ")";
    }

    /** 关系操作通用骨架：MATCH 两端 + MATCH 关系 + action（用于更新/删除/计数） */
    private String relCypher(RelRequest req, String action) {
        return relEndpoints(req) + "MATCH (a)-[r:`" + req.type() + "`]->(b) " + action;
    }

    /** 仅 MATCH 两端节点（用于 MERGE 创建） */
    private String relEndpoints(RelRequest req) {
        String fromKeyProp = schema.keyPropOf(req.fromLabel());
        String toKeyProp = schema.keyPropOf(req.toLabel());
        return "MATCH (a:`" + req.fromLabel() + "` {`" + fromKeyProp + "`: $from}) "
                + "MATCH (b:`" + req.toLabel() + "` {`" + toKeyProp + "`: $to}) ";
    }

    private Map<String, Object> relParams(RelRequest req) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("from", req.fromKey());
        params.put("to", req.toKey());
        params.put("props", req.props() == null ? Map.of() : req.props());
        return params;
    }

    private long nodeCount(String label, String keyProp, Object key) {
        return scalar("MATCH (n:`" + label + "` {`" + keyProp + "`: $key}) RETURN count(n) AS c",
                Map.of("key", key));
    }

    private long relCount(RelRequest req) {
        return scalar(relCypher(req, "RETURN count(r) AS c"), relParams(req));
    }

    private long scalar(String cypher, Map<String, Object> params) {
        return driver.executableQuery(cypher).withParameters(params).execute()
                .records().get(0).get("c").asLong();
    }

    private void write(String cypher, Map<String, Object> params) {
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run(cypher, params).consume();
                return null;
            });
        }
    }

    private long writeCount(String cypher, Map<String, Object> params) {
        try (Session session = driver.session()) {
            return session.executeWrite(tx -> tx.run(cypher, params).single().get("c").asLong());
        }
    }
}
