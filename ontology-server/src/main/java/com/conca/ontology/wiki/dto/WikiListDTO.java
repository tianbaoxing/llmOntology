package com.conca.ontology.wiki.dto;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Wiki 文档列表视图（列表只含 SUCCESS 条目，与 LLM 可见性一致）。
 *
 * @author tianbaoxing
 * @date 2026-09-23
 */
@Schema(description = "Wiki 文档列表（仅转换成功的 MD）")
public record WikiListDTO(
        @Schema(description = "文档列表") List<WikiDocDTO> docs) {
}
