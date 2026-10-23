package com.conca.ontology.graph.dto;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 删除类结果
 *
 * @author tianbaoxing
 * @date 2026-08-21
 */
@Schema(description = "删除类结果")
public record ClassDeleteResultDTO(
        @Schema(description = "已删除的类名", example = "Era") String deleted) {
}
