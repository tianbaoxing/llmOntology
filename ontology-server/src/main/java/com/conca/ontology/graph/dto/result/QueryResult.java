package com.conca.ontology.graph.dto.result;
import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 通用只读 Cypher 查询结果（结构由任意 Cypher 决定，rows 内容为动态键值对）
 *
 * @author tianbaoxing
 * @date 2026-08-21
 */
@Schema(description = "通用 Cypher 查询结果：结构由查询语句决定，rows 内为动态键值对")
public record QueryResult(
        @Schema(description = "每行记录的列名（按 RETURN 顺序）") List<String> keys,
        @Schema(description = "结果行列表，每行为 列名→值 的动态对象（节点/关系/路径已展开为 Map/List）")
        List<Map<String, Object>> rows,
        @Schema(description = "返回行数（本页条数，非全量总数）") int count) {
}
