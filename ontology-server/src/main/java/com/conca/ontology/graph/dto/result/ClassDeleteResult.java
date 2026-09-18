package com.conca.ontology.graph.dto.result;
import io.swagger.v3.oas.annotations.media.Schema;

/** 删除类结果 */
@Schema(description = "删除类结果")
public record ClassDeleteResult(
        @Schema(description = "已删除的类名", example = "Era") String deleted) {
}
