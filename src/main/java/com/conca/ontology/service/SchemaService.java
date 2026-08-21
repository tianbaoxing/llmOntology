package com.conca.ontology.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.conca.ontology.dto.ClassDeleteResult;
import com.conca.ontology.dto.ClassDetailView;
import com.conca.ontology.dto.ClassSummary;
import com.conca.ontology.dto.ClassView;
import com.conca.ontology.dto.IncomingRelation;
import com.conca.ontology.dto.QueryResult;
import com.conca.ontology.dto.RelationDetail;
import com.conca.ontology.dto.RelationView;
import com.conca.ontology.dto.SchemaView;
import com.conca.ontology.exception.ApiException;
import com.conca.ontology.model.ClassDef;
import com.conca.ontology.model.GraphSchema;
import com.conca.ontology.model.PropertyDef;
import com.conca.ontology.model.RelationDef;
import com.conca.ontology.model.SchemaFile;

/**
 * 概念层（schema）CRUD：graph-schema.json 为唯一事实源。
 * 首次启动初始化为空 schema（全新部署无预置概念）；每次变更原子落盘并热更新 GraphSchema 白名单。
 * 删除保护：类被其他类关系引用 / 库中仍有实例 → 409。
 */
@Service
public class SchemaService {

    private static final Logger log = LoggerFactory.getLogger(SchemaService.class);

    private final GraphSchema schema;
    private final GraphQueryService queryService;
    private final GraphImportService importService;
    private final ObjectMapper mapper;

    @Value("${graph.schema-file:data/graph-schema.json}")
    private String schemaFile;

    private volatile int version = 1;

    public SchemaService(GraphSchema schema, GraphQueryService queryService,
                         GraphImportService importService, ObjectMapper mapper) {
        this.schema = schema;
        this.queryService = queryService;
        this.importService = importService;
        this.mapper = mapper;
    }

    /** 启动加载：外部文件不存在则初始化为空 schema（全新部署，概念层由用户按场景构建） */
    public void init() {
        Path file = schemaPath();
        try {
            if (Files.notExists(file)) {
                Files.createDirectories(file.getParent());
                mapper.writerWithDefaultPrettyPrinter()
                        .writeValue(file.toFile(), new SchemaFile(1, List.of()));
                log.info("empty schema file initialized: {}", file.toAbsolutePath());
            }
            SchemaFile sf = mapper.readValue(file.toFile(), SchemaFile.class);
            this.version = sf.version();
            schema.reload(sf.classes());
            log.info("schema loaded: {} classes (version {})", schema.allClasses().size(), version);
        } catch (Exception e) {
            throw new IllegalStateException("failed to load schema file: " + file.toAbsolutePath(), e);
        }
    }

    /** 当前 schema 文件版本号（备份文件名 v 段数据源） */
    public int version() {
        return version;
    }

    // ---------------- views ----------------

    public SchemaView schemaView() {
        Map<String, Long> nodeCounts = nodeCounts();
        Map<String, Long> relCounts = relCounts();
        List<ClassView> classes = new ArrayList<>();
        for (ClassDef c : schema.allClasses()) {
            classes.add(classMap(c, nodeCounts, relCounts));
        }
        List<RelationView> rels = new ArrayList<>();
        for (GraphSchema.RelSignature sig : schema.allRelations()) {
            rels.add(relMap(sig, relCounts));
        }
        return new SchemaView(version, classes, rels);
    }

    public List<ClassSummary> classSummaries() {
        Map<String, Long> nodeCounts = nodeCounts();
        List<ClassSummary> list = new ArrayList<>();
        for (ClassDef c : schema.allClasses()) {
            list.add(new ClassSummary(c.name(), c.cnName(), c.layer(), c.keyProp(), c.description(),
                    nodeCounts.getOrDefault(c.name(), 0L),
                    c.properties().size(), c.relations().size()));
        }
        return list;
    }

    public List<RelationView> relationViews() {
        Map<String, Long> relCounts = relCounts();
        List<RelationView> list = new ArrayList<>();
        for (GraphSchema.RelSignature sig : schema.allRelations()) {
            list.add(relMap(sig, relCounts));
        }
        return list;
    }

    /** 单类详情：定义 + 实时 count + 出边（声明）+ 入边（推导） */
    public ClassDetailView classView(String name) {
        ClassDef def = schema.classOf(name);
        if (def == null) {
            throw new ApiException(404, "class not found: " + name);
        }
        Map<String, Long> nodeCounts = nodeCounts();
        Map<String, Long> relCounts = relCounts();
        ClassView view = classMap(def, nodeCounts, relCounts);
        List<IncomingRelation> incoming = new ArrayList<>();
        for (GraphSchema.RelSignature sig : schema.allRelations()) {
            if (sig.toClass().equals(name)) {
                incoming.add(new IncomingRelation(sig.def().name(), sig.def().cnName(),
                        sig.fromClass(), relCounts.getOrDefault(sig.def().name(), 0L)));
            }
        }
        return new ClassDetailView(view.name(), view.cnName(), view.layer(), view.keyProp(),
                view.description(), view.count(), view.properties(), view.relations(), incoming);
    }

    private ClassView classMap(ClassDef c, Map<String, Long> nodeCounts, Map<String, Long> relCounts) {
        List<RelationDetail> rels = new ArrayList<>();
        for (RelationDef r : c.relations()) {
            rels.add(relDetail(r, relCounts));
        }
        return new ClassView(c.name(), c.cnName(), c.layer(), c.keyProp(), c.description(),
                nodeCounts.getOrDefault(c.name(), 0L), c.properties(), rels);
    }

    private RelationDetail relDetail(RelationDef r, Map<String, Long> relCounts) {
        return new RelationDetail(r.name(), r.cnName(), r.toClass(), r.description(), r.example(),
                r.props(), relCounts.getOrDefault(r.name(), 0L));
    }

    private RelationView relMap(GraphSchema.RelSignature sig, Map<String, Long> relCounts) {
        return new RelationView(sig.def().name(), sig.def().cnName(), sig.fromClass(), sig.toClass(),
                sig.def().description(), sig.def().example(), sig.def().props(),
                relCounts.getOrDefault(sig.def().name(), 0L));
    }

    // ---------------- CRUD ----------------

    public ClassDetailView createClass(ClassDef def) {
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

    public ClassDetailView updateClass(String name, ClassDef def) {
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

    public ClassDeleteResult deleteClass(String name) {
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
        return new ClassDeleteResult(name);
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

    /** 原子落盘：临时文件 + rename，成功后热更新注册表 */
    private void save(List<ClassDef> classes) {
        Path file = schemaPath();
        try {
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), new SchemaFile(version + 1, classes));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            version = version + 1;
            schema.reload(classes);
        } catch (Exception e) {
            throw new IllegalStateException("failed to save schema file: " + file.toAbsolutePath(), e);
        }
    }

    private Path schemaPath() {
        return Path.of(schemaFile).toAbsolutePath();
    }

    private Map<String, Long> nodeCounts() {
        return countMap("MATCH (n) RETURN labels(n)[0] AS k, count(*) AS c");
    }

    private Map<String, Long> relCounts() {
        return countMap("MATCH ()-[r]->() RETURN type(r) AS k, count(*) AS c");
    }

    private Map<String, Long> countMap(String cypher) {
        QueryResult result = queryService.query(cypher, null);
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
        QueryResult result = queryService.query(cypher, null);
        return ((Number) result.rows().get(0).get("c")).longValue();
    }
}
