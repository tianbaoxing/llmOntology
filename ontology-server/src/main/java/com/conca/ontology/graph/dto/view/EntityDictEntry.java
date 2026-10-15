package com.conca.ontology.graph.dto.view;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 实体词典条目：全量图谱节点（概念层 + 实例层）的匹配四元组，供 wiki 正文实体链接（服务层内部，不出 HTTP）。
 *
 * @author tianbaoxing
 * @date 2026-09-30
 */
@Schema(description = "实体词典条目：label/key/name/layer")
public record EntityDictEntry(
        @Schema(description = "节点标签（schema 白名单内）", example = "Site") String label,
        @Schema(description = "唯一键值（keyProp 属性值）", example = "三星堆遗址") Object key,
        @Schema(description = "显示名称：props.title ?? props.name ?? props.cnName ?? key（与 instances 页一致）",
                example = "三星堆遗址") String name,
        @Schema(description = "层归属（schema 声明）：concept=概念层 / instance=实例层", example = "instance") String layer) {
}
