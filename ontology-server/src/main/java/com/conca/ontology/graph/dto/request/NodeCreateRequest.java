package com.conca.ontology.graph.dto.request;
import java.util.Map;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import io.swagger.v3.oas.annotations.media.Schema;

/** 单节点创建（MERGE 幂等，存在则覆盖属性） */
@Schema(description = "单节点创建请求（MERGE 幂等：已存在则覆盖属性）")
public record NodeCreateRequest(
        @Schema(description = "节点标签（须在 schema 白名单内）", example = "Era")
        @NotBlank String label,
        @Schema(description = "节点属性键值对：必须包含该类 keyProp 的值（唯一键），其余属性按 schema 定义",
                example = "{\"name\": \"__doc_test__\", \"cnName\": \"文档测试\"}")
        @NotEmpty Map<String, Object> props) {
}
