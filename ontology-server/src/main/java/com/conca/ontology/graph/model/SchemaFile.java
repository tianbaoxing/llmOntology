package com.conca.ontology.graph.model;
import java.util.List;

/**
 * graph-schema.json 文件结构（概念层唯一事实源）
 *
 * @author tianbaoxing
 * @date 2026-08-19
 */
public record SchemaFile(int version, List<ClassDef> classes) {
}
