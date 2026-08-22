package com.conca.ontology.dto.request;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;

/** 通用只读 Cypher 查询请求 */
@Schema(description = "通用只读 Cypher 查询请求（写语句会被拒绝）")
public record QueryRequest(
        @Schema(description = "只读 Cypher 语句（MATCH/RETURN/OPTIONAL/UNWIND/WITH 等；"
                + "CREATE/MERGE/DELETE/SET/DROP/CALL 等写语句开头即拒 400）",
                example = "MATCH (n:Era) RETURN n.name AS name LIMIT 5")
        @NotBlank(message = "cypher must not be blank") String cypher,
        @Schema(description = "Cypher 参数绑定：键名对应语句中的 $占位符（可空）",
                example = "{\"name\": \"战国\"}") Map<String, Object> params) {
}
