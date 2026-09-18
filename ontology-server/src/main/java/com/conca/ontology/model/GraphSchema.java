package com.conca.ontology.model;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.conca.ontology.shared.web.ApiException;

/**
 * 图谱白名单与元数据注册表：从 graph-schema.json 加载（唯一事实源），支持热更新。
 * 所有拼接进 Cypher 的标识符（label / keyProp / 关系类型）必须先经过此处校验（防注入）。
 */
@Component
public class GraphSchema {

    /** 合法标识符：label / 属性名 / 关系类型（防 Cypher 注入的第一道闸） */
    private static final Pattern IDENT = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    public static boolean isValidIdentifier(String s) {
        return s != null && IDENT.matcher(s).matches();
    }

    /** 关系签名：声明于 fromClass 一侧，含方向 */
    public record RelSignature(String fromClass, RelationDef def) {
        public String type() {
            return def.name();
        }

        public String toClass() {
            return def.toClass();
        }
    }

    private record State(List<ClassDef> classes, Map<String, RelSignature> rels) {
    }

    private volatile State state = new State(List.of(), Map.of());

    /** 整体替换注册表（文件加载 / SchemaService CRUD 落盘后调用） */
    public synchronized void reload(List<ClassDef> classes) {
        List<ClassDef> normalized = classes.stream().map(GraphSchema::normalize).toList();
        Map<String, RelSignature> rels = new LinkedHashMap<>();
        for (ClassDef c : normalized) {
            for (RelationDef r : c.relations()) {
                rels.put(r.name(), new RelSignature(c.name(), r));
            }
        }
        this.state = new State(List.copyOf(normalized),
                Collections.unmodifiableMap(new LinkedHashMap<>(rels)));
    }

    public Collection<ClassDef> allClasses() {
        return state.classes();
    }

    public ClassDef classOf(String label) {
        for (ClassDef c : state.classes()) {
            if (c.name().equals(label)) {
                return c;
            }
        }
        return null;
    }

    public Collection<RelSignature> allRelations() {
        return state.rels().values();
    }

    public void checkLabel(String label) {
        if (classOf(label) == null) {
            throw new ApiException(400, "unknown label: " + label
                    + ", allowed: " + state.classes().stream().map(ClassDef::name).toList());
        }
    }

    public String keyPropOf(String label) {
        ClassDef c = classOf(label);
        if (c == null) {
            throw new ApiException(400, "unknown label: " + label);
        }
        return c.keyProp();
    }

    public RelationDef relationOf(String type) {
        RelSignature sig = state.rels().get(type);
        return sig == null ? null : sig.def();
    }

    public void checkRelType(String type) {
        if (relationOf(type) == null) {
            throw new ApiException(400, "unknown relationship type: " + type
                    + ", allowed: " + state.rels().keySet());
        }
    }

    /** 校验关系签名：type 必须已声明，且 from/to 与声明一致 */
    public void checkRelSignature(String type, String fromLabel, String toLabel) {
        RelSignature sig = state.rels().get(type);
        if (sig == null) {
            throw new ApiException(400, "unknown relationship type: " + type
                    + ", allowed: " + state.rels().keySet());
        }
        if (!sig.fromClass().equals(fromLabel) || !sig.toClass().equals(toLabel)) {
            throw new ApiException(400, "relation " + type + " is declared as ("
                    + sig.fromClass() + ")-[:" + type + "]->(" + sig.toClass()
                    + "), got (" + fromLabel + ")->(" + toLabel + ")");
        }
    }

    /** 归一化：空列表兜底 + 标识符合法性校验（文件可能被手工编辑，防注入兜底） */
    private static ClassDef normalize(ClassDef c) {
        requireIdent(c.name(), "class name");
        requireIdent(c.keyProp(), "keyProp");
        List<PropertyDef> props = c.properties() == null ? List.of() : List.copyOf(c.properties());
        for (PropertyDef p : props) {
            requireIdent(p.name(), "property name");
        }
        List<RelationDef> rels = c.relations() == null
                ? List.of()
                : c.relations().stream().map(GraphSchema::normalizeRel).toList();
        return new ClassDef(c.name(), c.cnName(), c.layer(), c.keyProp(), c.description(), props, rels);
    }

    private static RelationDef normalizeRel(RelationDef r) {
        requireIdent(r.name(), "relation name");
        requireIdent(r.toClass(), "relation toClass");
        if (r.props() != null) {
            for (PropertyDef p : r.props()) {
                requireIdent(p.name(), "relation property name");
            }
        }
        return r.props() == null
                ? new RelationDef(r.name(), r.cnName(), r.toClass(), r.description(), r.example(), List.of())
                : r;
    }

    private static void requireIdent(String s, String what) {
        if (!isValidIdentifier(s)) {
            throw new IllegalArgumentException("invalid identifier (" + what + "): " + s);
        }
    }
}