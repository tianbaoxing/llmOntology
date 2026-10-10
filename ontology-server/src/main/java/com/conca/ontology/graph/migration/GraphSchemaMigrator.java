package com.conca.ontology.graph.migration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.conca.ontology.graph.entity.GraphSchemaMeta;
import com.conca.ontology.graph.mapper.GraphClassMapper;
import com.conca.ontology.graph.mapper.GraphClassPropMapper;
import com.conca.ontology.graph.mapper.GraphRelDefMapper;
import com.conca.ontology.graph.mapper.GraphSchemaMetaMapper;
import com.conca.ontology.graph.model.ClassDef;
import com.conca.ontology.graph.model.GraphSchema;
import com.conca.ontology.graph.model.PropertyDef;
import com.conca.ontology.graph.model.RelationDef;
import com.conca.ontology.graph.model.GraphSchemaConverter;
import com.conca.ontology.graph.model.SchemaFile;
import com.conca.ontology.graph.service.GraphImportService;
import com.conca.ontology.shared.db.migration.JsonMigrator;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * graph-schema.json → graph_class / graph_class_prop / graph_rel_def + graph_schema_meta 存量迁移（P2）。
 * 导入用编程式事务（TransactionTemplate）而非 @Transactional：内存白名单（GraphSchema）重载与
 * Neo4j 唯一约束补建必须在事务提交后执行，回滚时不污染内存注册表。
 * 建约束为 best-effort：GraphInitializer 先于本迁移器运行时（当前默认顺序），此处是导入类建约束的
 * 唯一时机；反之已建过，CREATE CONSTRAINT IF NOT EXISTS 幂等。失败仅告警，下次重启由
 * GraphInitializer 从库加载后补建。
 */
@Component
@Order(30)
public class GraphSchemaMigrator implements JsonMigrator {

    private static final Logger log = LoggerFactory.getLogger(GraphSchemaMigrator.class);

    private final ObjectMapper mapper;
    private final GraphClassMapper classMapper;
    private final GraphClassPropMapper propMapper;
    private final GraphRelDefMapper relMapper;
    private final GraphSchemaMetaMapper metaMapper;
    private final GraphSchema schema;
    private final GraphImportService importService;
    private final TransactionTemplate txTemplate;
    private final String schemaFile;

    public GraphSchemaMigrator(ObjectMapper mapper, GraphClassMapper classMapper,
            GraphClassPropMapper propMapper, GraphRelDefMapper relMapper, GraphSchemaMetaMapper metaMapper,
            GraphSchema schema, GraphImportService importService, TransactionTemplate txTemplate,
            @Value("${graph.schema-file:data/graph-schema.json}") String schemaFile) {
        this.mapper = mapper;
        this.classMapper = classMapper;
        this.propMapper = propMapper;
        this.relMapper = relMapper;
        this.metaMapper = metaMapper;
        this.schema = schema;
        this.importService = importService;
        this.txTemplate = txTemplate;
        this.schemaFile = schemaFile;
    }

    @Override
    public String name() {
        return "graph-schema";
    }

    @Override
    public Path sourceFile() {
        return Path.of(schemaFile).toAbsolutePath().normalize();
    }

    @Override
    public boolean targetTableEmpty() {
        return classMapper.selectCount(null) == 0;
    }

    @Override
    public void importAll() throws Exception {
        SchemaFile sf = mapper.readValue(Files.readString(sourceFile()), SchemaFile.class);
        List<ClassDef> classes = sf.classes() == null ? List.of() : sf.classes();
        txTemplate.executeWithoutResult(status -> {
            // 整表重写（对齐原「整文件原子落盘」语义），数据量为个位数类，成本可忽略
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
            upsertMeta(sf.version());
        });
        // 事务提交后：热更新内存白名单 + 幂等补建 Neo4j 唯一约束
        schema.reload(classes);
        try {
            importService.createConstraints();
        } catch (Exception e) {
            log.warn("[graph-schema] 导入后补建 Neo4j 唯一约束失败（下次重启自动补建）：{}", e.getMessage());
        }
        log.info("graph-schema.json 导入完成：{} 个类（version={}）", classes.size(), sf.version());
    }

    @Override
    public void initDefaults() {
        // 全新部署：空 schema + version=1，对齐原「初始化空 schema 文件 v1」语义；
        // SchemaService.init() 亦会兜底创建，此处幂等
        if (metaMapper.selectById(GraphSchemaMeta.SINGLETON_ID) == null) {
            metaMapper.insert(metaRow(1));
        }
    }

    private void upsertMeta(int version) {
        GraphSchemaMeta existing = metaMapper.selectById(GraphSchemaMeta.SINGLETON_ID);
        if (existing == null) {
            metaMapper.insert(metaRow(version));
        } else {
            GraphSchemaMeta next = metaRow(version);
            metaMapper.updateById(next);
        }
    }

    private static GraphSchemaMeta metaRow(int version) {
        GraphSchemaMeta meta = new GraphSchemaMeta();
        meta.setId(GraphSchemaMeta.SINGLETON_ID);
        meta.setVersion(version);
        meta.setUpdateTime(LocalDateTime.now());
        return meta;
    }
}
