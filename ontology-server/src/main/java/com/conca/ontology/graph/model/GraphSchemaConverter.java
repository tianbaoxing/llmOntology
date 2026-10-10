package com.conca.ontology.graph.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.conca.ontology.graph.entity.GraphClass;
import com.conca.ontology.graph.entity.GraphClassProp;
import com.conca.ontology.graph.entity.GraphRelDef;

/**
 * {@link GraphClass}/{@link GraphClassProp}/{@link GraphRelDef} 行 ↔ {@link ClassDef} 模型转换器（P2）。
 * SchemaService 保存与 GraphSchemaMigrator 导入共用，保证行映射唯一实现；ord 逐组递增保持文件内声明顺序。
 */
public final class GraphSchemaConverter {

    private GraphSchemaConverter() {
    }

    public static GraphClass toClassRow(ClassDef c, int ord) {
        GraphClass row = new GraphClass();
        row.setName(c.name());
        row.setCnName(c.cnName());
        row.setLayer(c.layer());
        row.setKeyProp(c.keyProp());
        row.setDescription(c.description());
        row.setOrd(ord);
        return row;
    }

    public static GraphClassProp toPropRow(String className, PropertyDef p, int ord) {
        GraphClassProp row = new GraphClassProp();
        row.setClassName(className);
        row.setPropName(p.name());
        row.setCnName(p.cnName());
        row.setDataType(p.dataType());
        row.setRequired(p.required());
        row.setDescription(p.description());
        row.setOrd(ord);
        return row;
    }

    public static GraphRelDef toRelRow(String fromClass, RelationDef r, int ord) {
        GraphRelDef row = new GraphRelDef();
        row.setFromClass(fromClass);
        row.setRelName(r.name());
        row.setCnName(r.cnName());
        row.setToClass(r.toClass());
        row.setDescription(r.description());
        row.setExample(r.example());
        row.setProps(r.props() == null ? List.of() : r.props());
        row.setOrd(ord);
        return row;
    }

    /** 三表行 → 按声明顺序组装的 ClassDef 列表（props/rels 按各自 ord 排序挂回所属类） */
    public static List<ClassDef> toClassDefs(List<GraphClass> classes, List<GraphClassProp> props,
            List<GraphRelDef> rels) {
        Map<String, List<PropertyDef>> propsByClass = new LinkedHashMap<>();
        for (GraphClassProp p : props) {
            propsByClass.computeIfAbsent(p.getClassName(), k -> new ArrayList<>())
                    .add(new PropertyDef(p.getPropName(), p.getCnName(), p.getDataType(),
                            Boolean.TRUE.equals(p.getRequired()), p.getDescription()));
        }
        Map<String, List<RelationDef>> relsByClass = new LinkedHashMap<>();
        for (GraphRelDef r : rels) {
            relsByClass.computeIfAbsent(r.getFromClass(), k -> new ArrayList<>())
                    .add(new RelationDef(r.getRelName(), r.getCnName(), r.getToClass(), r.getDescription(),
                            r.getExample(), r.getProps()));
        }
        List<ClassDef> out = new ArrayList<>(classes.size());
        for (GraphClass c : classes) {
            out.add(new ClassDef(c.getName(), c.getCnName(), c.getLayer(), c.getKeyProp(), c.getDescription(),
                    propsByClass.getOrDefault(c.getName(), List.of()),
                    relsByClass.getOrDefault(c.getName(), List.of())));
        }
        return out;
    }
}
