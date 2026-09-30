package com.conca.ontology.wiki.dto.view;
import io.swagger.v3.oas.annotations.media.Schema;

/** 单篇文档正文实际命中的图谱实体（后端预筛，前端只包裹不匹配）。 */
@Schema(description = "文档正文命中的图谱实体")
public record WikiEntityView(
        @Schema(description = "实体显示名称（词典 name）", example = "三星堆遗址") String name,
        @Schema(description = "节点标签（跳转 /instances 的 label 参数）", example = "Site") String label,
        @Schema(description = "唯一键值", example = "三星堆遗址") Object key,
        @Schema(description = "层归属：concept=概念层 / instance=实例层", example = "instance") String layer,
        @Schema(description = "该文档正文命中次数") long count) {
}
