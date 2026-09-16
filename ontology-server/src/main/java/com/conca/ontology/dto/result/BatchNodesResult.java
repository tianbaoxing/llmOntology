package com.conca.ontology.dto.result;

import io.swagger.v3.oas.annotations.media.Schema;

/** 批量写入节点结果（UNWIND + MERGE 幂等） */
@Schema(description = "批量写入节点结果（UNWIND + MERGE 幂等）")
public record BatchNodesResult(
        @Schema(description = "节点标签", example = "Era") String label,
        @Schema(description = "实际写入条数（含新建与覆盖）", example = "100") int written) {
}
