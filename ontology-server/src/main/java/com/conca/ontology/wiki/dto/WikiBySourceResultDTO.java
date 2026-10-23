package com.conca.ontology.wiki.dto;
import java.util.List;


import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 按来源文件查相关 wiki 文档（实例详情反向溯源，字符串相等：两侧均源自 FileEntry.name）。
 *
 * @author tianbaoxing
 * @date 2026-09-30
 */
@Schema(description = "按来源文件查询的相关文档列表")
public record WikiBySourceResultDTO(
        @Schema(description = "相关文档列表（sourceName 与 file 相等；可为空列表）") List<WikiDocDTO> docs) {
}
