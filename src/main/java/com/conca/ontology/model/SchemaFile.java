package com.conca.ontology.model;

import java.util.List;

/** graph-schema.json 文件结构（概念层唯一事实源） */
public record SchemaFile(int version, List<ClassDef> classes) {
}
