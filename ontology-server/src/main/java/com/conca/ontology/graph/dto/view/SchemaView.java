package com.conca.ontology.graph.dto.view;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 全量 schema 视图：版本 + 类定义列表 + 关系扁平列表（含实时 count）
 *
 * @author tianbaoxing
 * @date 2026-08-21
 */
@Schema(description = "全量 schema 视图：版本 + 类定义列表 + 关系扁平列表（含实时 count）")
public record SchemaView(
        @Schema(description = "schema 文件版本号（每次变更 +1）", example = "12") int version,
        @Schema(description = "类（节点类型）定义列表") List<ClassView> classes,
        @Schema(description = "关系扁平视图（全量，每类型一条）") List<RelationView> relations) {
}
