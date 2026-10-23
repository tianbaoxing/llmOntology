package com.conca.ontology.graph.service;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.conca.ontology.graph.converter.GraphSchemaConverter;
import com.conca.ontology.graph.dto.ClassDeleteResultDTO;
import com.conca.ontology.graph.dto.ClassDetailDTO;
import com.conca.ontology.graph.dto.ClassSummaryDTO;
import com.conca.ontology.graph.dto.ClassDTO;
import com.conca.ontology.graph.dto.IncomingRelationDTO;
import com.conca.ontology.graph.dto.QueryResultDTO;
import com.conca.ontology.graph.dto.RelationDetailDTO;
import com.conca.ontology.graph.dto.RelationDTO;
import com.conca.ontology.graph.dto.SchemaDTO;
import com.conca.ontology.graph.entity.GraphClass;
import com.conca.ontology.graph.entity.GraphClassProp;
import com.conca.ontology.graph.entity.GraphRelDef;
import com.conca.ontology.graph.entity.GraphSchemaMeta;
import com.conca.ontology.graph.mapper.GraphClassMapper;
import com.conca.ontology.graph.mapper.GraphClassPropMapper;
import com.conca.ontology.graph.mapper.GraphRelDefMapper;
import com.conca.ontology.graph.mapper.GraphSchemaMetaMapper;
import com.conca.ontology.shared.web.ApiException;
import com.conca.ontology.graph.model.ClassDef;
import com.conca.ontology.graph.model.GraphSchema;
import com.conca.ontology.graph.model.PropertyDef;
import com.conca.ontology.graph.model.RelationDef;

/**
 * 概念层（schema）CRUD：MySQL 三表（graph_class / graph_class_prop / graph_rel_def）+
 * graph_schema_meta 为唯一事实源（P2 起，由 graph-schema.json 迁移而来）。
 * 每次变更整表事务重写并热更新 GraphSchema 白名单（对齐原整文件原子落盘语义）。
 * 删除保护：类被其他类关系引用 / 库中仍有实例 → 409。
 *
 * @author tianbaoxing
 * @date 2026-08-19
 */
@Service
public class SchemaService {

    private static final Logger log = LoggerFactory.getLogger(SchemaService.class);

    private final GraphSchema schema;
    private final GraphQueryService queryService;
    private final GraphImportService importService;
    private final GraphClassMapper classMapper;
    private final GraphClassPropMapper propMapper;
    private final GraphRelDefMapper relMapper;
    private final GraphSchemaMetaMapper metaMapper;
    private final TransactionTemplate txTemplate;

    private volatile int version = 1;

    public SchemaService(GraphSchema schema, GraphQueryService queryService,
                         GraphImportService importService, GraphClassMapper classMapper,
                         GraphClassPropMapper propMapper, GraphRelDefMapper relMapper,
                         GraphSchemaMetaMapper metaMapper, TransactionTemplate txTemplate) {
        this.schema = schema;
        this.queryService = queryService;
        this.importService = importService;
        this.classMapper = classMapper;
        this.propMapper = propMapper;
        this.relMapper = relMapper;
        this.metaMapper = metaMapper;
        this.txTemplate = txTemplate;
    }

    /** 启动加载：MySQL 唯一事实源；meta 缺行时初始化空 schema v1（全新部署，概念层由用户按场景构建） */
    public void init() {
        try {
            this.version = ensureMeta();
            List<ClassDef> classes = loadClasses();
            schema.reload(classes);
            log.info("schema loaded: {} classes (version {})", classes.size(), version);
        } catch (Exception e) {
            throw new IllegalStateException("failed to load schema from db", e);
        }
    }

    /** 当前 schema 版本号（备份文件名 v 段数据源） */
    public int version() {
        return version;
    }

    // ---------------- views ----------------

    public SchemaDTO schemaView() {
        Map<String, Long> nodeCounts = nodeCounts();
        Map<String, Long> relCounts = relCounts();
        List<ClassDTO> classes = new ArrayList<>();
        for (ClassDef c : schema.allClasses()) {
            classes.add(classMap(c, nodeCounts, relCounts));
        }
        List<RelationDTO> rels = new ArrayList<>();
        for (GraphSchema.RelSignature sig : schema.allRelations()) {
            rels.add(relMap(sig, relCounts));
        }
        return new SchemaDTO(version, classes, rels);
    }

    public List<ClassSummaryDTO> classSummaries() {
        Map<String, Long> nodeCounts = nodeCounts();
        List<ClassSummaryDTO> list = new ArrayList<>();
        for (ClassDef c : schema.allClasses()) {
            list.add(new ClassSummaryDTO(c.name(), c.cnName(), c.layer(), c.keyProp(), c.description(),
                    nodeCounts.getOrDefault(c.name(), 0L),
                    c.properties().size(), c.relations().size()));
        }
        return list;
    }

    public List<RelationDTO> relationViews() {
        Map<String, Long> relCounts = relCounts();
        List<RelationDTO> list = new ArrayList<>();
        for (GraphSchema.RelSignature sig : schema.allRelations()) {
            list.add(relMap(sig, relCounts));
        }
        return list;
    }

    /** 单类详情：定义 + 实时 count + 出边（声明）+ 入边（推导） */
    public ClassDetailDTO classView(String name) {
        ClassDef def = schema.classOf(name);
        if (def == null) {
            throw new ApiException(404, "class not found: " + name);
        }
        Map<String, Long> nodeCounts = nodeCounts();
        Map<String, Long> relCounts = relCounts();
        ClassDTO view = classMap(def, nodeCounts, relCounts);
        List<IncomingRelationDTO> incoming = new ArrayList<>();
        for (GraphSchema.RelSignature sig : schema.allRelations()) {
            if (sig.toClass().equals(name)) {
                incoming.add(new IncomingRelationDTO(sig.def().name(), sig.def().cnName(),
                        sig.fromClass(), relCounts.getOrDefault(sig.def().name(), 0L)));
            }
        }
        return new ClassDetailDTO(view.name(), view.cnName(), view.layer(), view.keyProp(),
                view.description(), view.count(), view.properties(), view.relations(), incoming);
    }

    private ClassDTO classMap(ClassDef c, Map<String, Long> nodeCounts, Map<String, Long> relCounts) {
        List<RelationDetailDTO> rels = new ArrayList<>();
        for (RelationDef r : c.relations()) {
            rels.add(relDetail(r, relCounts));
        }
        return new ClassDTO(c.name(), c.cnName(), c.layer(), c.keyProp(), c.description(),
                nodeCounts.getOrDefault(c.name(), 0L), c.properties(), rels);
    }

    private RelationDetailDTO relDetail(RelationDef r, Map<String, Long> relCounts) {
        return new RelationDetailDTO(r.name(), r.cnName(), r.toClass(), r.description(), r.example(),
                r.props(), relCounts.getOrDefault(r.name(), 0L));
    }

    private RelationDTO relMap(GraphSchema.RelSignature sig, Map<String, Long> relCounts) {
        return new RelationDTO(sig.def().name(), sig.def().cnName(), sig.fromClass(), sig.toClass(),
                sig.def().description(), sig.def().example(), sig.def().props(),
                relCounts.getOrDefault(sig.def().name(), 0L));
    }

    // ---------------- CRUD ----------------

    public ClassDetailDTO createClass(ClassDef def) {
        if (def == null) {
            throw new ApiException(400, "class def must not be null");
        }
        validate(def, null);
        if (schema.classOf(def.name()) != null) {
            throw new ApiException(409, "class already exists: " + def.name());
        }
        List<ClassDef> classes = new ArrayList<>(schema.allClasses());
        classes.add(def);
        save(classes);
        importService.createConstraint(def.name(), def.keyProp());
        log.info("schema class created: {} (keyProp={})", def.name(), def.keyProp());
        return classView(def.name());
    }

    public ClassDetailDTO updateClass(String name, ClassDef def) {
        ClassDef existing = schema.classOf(name);
        if (existing == null) {
            throw new ApiException(404, "class not found: " + name);
        }
        if (def == null || !name.equals(def.name())) {
            throw new ApiException(400, "class name immutable: " + name);
        }
        if (!existing.keyProp().equals(def.keyProp())) {
            throw new ApiException(400, "keyProp immutable: " + existing.keyProp() + ", got " + def.keyProp());
        }
        validate(def, name);
        checkDroppedRelations(existing, def);
        List<ClassDef> classes = schema.allClasses().stream()
                .map(c -> c.name().equals(name) ? def : c).toList();
        save(classes);
        log.info("schema class updated: {}", name);
        return classView(def.name());
    }

    public ClassDeleteResultDTO deleteClass(String name) {
        ClassDef def = schema.classOf(name);
        if (def == null) {
            throw new ApiException(404, "class not found: " + name);
        }
        for (ClassDef other : schema.allClasses()) {
            if (other.name().equals(name)) {
                continue;
            }
            for (RelationDef r : other.relations()) {
                if (r.toClass().equals(name)) {
                    throw new ApiException(409, "class " + name + " referenced by "
                            + other.name() + "." + r.name() + "; remove that relation first");
                }
            }
        }
        long count = countNodes(name);
        if (count > 0) {
            throw new ApiException(409, "class " + name + " has " + count + " nodes; delete them first");
        }
        List<ClassDef> classes = schema.allClasses().stream()
                .filter(c -> !c.name().equals(name)).toList();
        save(classes);
        importService.dropConstraint(name, def.keyProp());
        log.info("schema class deleted: {}", name);
        return new ClassDeleteResultDTO(name);
    }

    // ---------------- validation ----------------

    private void validate(ClassDef def, String replaceName) {
        if (!GraphSchema.isValidIdentifier(def.name())) {
            throw new ApiException(400, "invalid class name (need [A-Za-z_][A-Za-z0-9_]*): " + def.name());
        }
        if (!GraphSchema.isValidIdentifier(def.keyProp())) {
            throw new ApiException(400, "invalid keyProp: " + def.keyProp());
        }
        if (!"concept".equals(def.layer()) && !"instance".equals(def.layer())) {
            throw new ApiException(400, "layer must be concept or instance: " + def.layer());
        }
        if (def.cnName() == null || def.cnName().isBlank()) {
            throw new ApiException(400, "cnName must not be blank");
        }
        if (def.properties() == null || def.properties().isEmpty()) {
            throw new ApiException(400, "properties must not be empty");
        }
        Set<String> propSeen = new HashSet<>();
        boolean hasKeyProp = false;
        for (PropertyDef p : def.properties()) {
            if (!GraphSchema.isValidIdentifier(p.name())) {
                throw new ApiException(400, "invalid property name: " + p.name());
            }
            if (!propSeen.add(p.name())) {
                throw new ApiException(400, "duplicate property in class " + def.name() + ": " + p.name());
            }
            if (p.name().equals(def.keyProp())) {
                hasKeyProp = true;
            }
        }
        if (!hasKeyProp) {
            throw new ApiException(400, "properties must contain keyProp `" + def.keyProp() + "`");
        }
        Set<String> relSeen = new HashSet<>();
        List<RelationDef> rels = def.relations() == null ? List.of() : def.relations();
        for (RelationDef r : rels) {
            if (!GraphSchema.isValidIdentifier(r.name())) {
                throw new ApiException(400, "invalid relation name: " + r.name());
            }
            if (!relSeen.add(r.name())) {
                throw new ApiException(400, "duplicate relation in class " + def.name() + ": " + r.name());
            }
            boolean self = def.name().equals(r.toClass());
            if (!self && schema.classOf(r.toClass()) == null) {
                throw new ApiException(400, "relation " + r.name() + " references unknown toClass: " + r.toClass());
            }
            for (ClassDef other : schema.allClasses()) {
                if (other.name().equals(replaceName) || other.name().equals(def.name())) {
                    continue;
                }
                for (RelationDef or : other.relations()) {
                    if (or.name().equals(r.name())) {
                        throw new ApiException(400, "relation name already declared in class "
                                + other.name() + ": " + r.name());
                    }
                }
            }
            if (r.props() != null) {
                Set<String> rpSeen = new HashSet<>();
                for (PropertyDef p : r.props()) {
                    if (!GraphSchema.isValidIdentifier(p.name())) {
                        throw new ApiException(400, "invalid relation property name: " + p.name());
                    }
                    if (!rpSeen.add(p.name())) {
                        throw new ApiException(400, "duplicate relation property: " + p.name());
                    }
                }
            }
        }
    }

    /** PUT 整体替换时：被移除或改向的关系若库中仍有实例 → 409 */
    private void checkDroppedRelations(ClassDef existing, ClassDef next) {
        Map<String, RelationDef> nextRels = new LinkedHashMap<>();
        for (RelationDef r : next.relations() == null ? List.<RelationDef>of() : next.relations()) {
            nextRels.put(r.name(), r);
        }
        for (RelationDef old : existing.relations()) {
            RelationDef now = nextRels.get(old.name());
            boolean dropped = now == null || !now.toClass().equals(old.toClass());
            if (dropped) {
                long count = countRels(old.name());
                if (count > 0) {
                    throw new ApiException(409, "relation " + old.name() + " has " + count
                            + " instances; delete them before removing/changing it");
                }
            }
        }
    }

    // ---------------- persistence & counts ----------------

    /** meta 行存在返回其 version；缺失则初始化 version=1 空行（幂等，兼全新部署默认初始化） */
    private int ensureMeta() {
        GraphSchemaMeta meta = metaMapper.selectById(GraphSchemaMeta.SINGLETON_ID);
        if (meta != null) {
            return meta.getVersion();
        }
        metaMapper.insert(metaRow(1));
        return 1;
    }

    private void upsertMeta(int version) {
        GraphSchemaMeta meta = metaMapper.selectById(GraphSchemaMeta.SINGLETON_ID);
        if (meta == null) {
            metaMapper.insert(metaRow(version));
        } else {
            metaMapper.updateById(metaRow(version));
        }
    }

    private static GraphSchemaMeta metaRow(int version) {
        GraphSchemaMeta meta = new GraphSchemaMeta();
        meta.setId(GraphSchemaMeta.SINGLETON_ID);
        meta.setVersion(version);
        meta.setUpdateTime(LocalDateTime.now());
        return meta;
    }

    /** 三表按 ord 读出并组装为声明顺序的 ClassDef 列表 */
    private List<ClassDef> loadClasses() {
        List<GraphClass> classes = classMapper.selectList(Wrappers.<GraphClass>lambdaQuery()
                .orderByAsc(GraphClass::getOrd));
        List<GraphClassProp> props = propMapper.selectList(Wrappers.<GraphClassProp>lambdaQuery()
                .orderByAsc(GraphClassProp::getClassName, GraphClassProp::getOrd));
        List<GraphRelDef> rels = relMapper.selectList(Wrappers.<GraphRelDef>lambdaQuery()
                .orderByAsc(GraphRelDef::getFromClass, GraphRelDef::getOrd));
        return GraphSchemaConverter.toClassDefs(classes, props, rels);
    }

    /**
     * 原子落库：事务内三表整表重写 + meta version+1（对齐原「临时文件 + rename 整文件落盘」语义；
     * 数据量为个位数类，整表重写成本可忽略），提交成功后热更新注册表。
     */
    private void save(List<ClassDef> classes) {
        int next = version + 1;
        txTemplate.executeWithoutResult(status -> {
            classMapper.delete(Wrappers.emptyWrapper());
            propMapper.delete(Wrappers.emptyWrapper());
            relMapper.delete(Wrappers.emptyWrapper());
            for (int i = 0; i < classes.size(); i++) {
                ClassDef c = classes.get(i);
                classMapper.insert(GraphSchemaConverter.toClassRow(c, i));
                List<PropertyDef> props = c.properties() == null ? List.of() : c.properties();
                for (int j = 0; j < props.size(); j++) {
                    propMapper.insert(GraphSchemaConverter.toPropRow(c.name(), props.get(j), j));
                }
                List<RelationDef> rels = c.relations() == null ? List.of() : c.relations();
                for (int k = 0; k < rels.size(); k++) {
                    relMapper.insert(GraphSchemaConverter.toRelRow(c.name(), rels.get(k), k));
                }
            }
            upsertMeta(next);
        });
        version = next;
        schema.reload(classes);
    }

    private Map<String, Long> nodeCounts() {
        return countMap("MATCH (n) RETURN labels(n)[0] AS k, count(*) AS c");
    }

    private Map<String, Long> relCounts() {
        return countMap("MATCH ()-[r]->() RETURN type(r) AS k, count(*) AS c");
    }

    private Map<String, Long> countMap(String cypher) {
        QueryResultDTO result = queryService.query(cypher, null);
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Map<String, Object> m : result.rows()) {
            counts.put((String) m.get("k"), ((Number) m.get("c")).longValue());
        }
        return counts;
    }

    private long countNodes(String label) {
        return scalar("MATCH (n:`" + label + "`) RETURN count(*) AS c");
    }

    private long countRels(String type) {
        return scalar("MATCH ()-[r:`" + type + "`]->() RETURN count(*) AS c");
    }

    private long scalar(String cypher) {
        QueryResultDTO result = queryService.query(cypher, null);
        return ((Number) result.rows().get(0).get("c")).longValue();
    }
}
