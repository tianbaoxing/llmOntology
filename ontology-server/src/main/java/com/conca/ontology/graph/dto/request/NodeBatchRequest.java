package com.conca.ontology.graph.dto.request;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 批量节点写入请求。
 * label 必须在白名单内；keyProp 以服务端 GraphSchema 为准（忽略客户端传入）。
 * items 中每条 map 必须包含唯一键属性，其余键值对作为节点属性写入。
 */
@Schema(description = "批量节点写入请求（UNWIND + MERGE 幂等）")
public record NodeBatchRequest(
        @Schema(description = "节点标签（须在 schema 白名单内）", example = "Era") @NotBlank String label,
        @Schema(description = "已废弃：唯一键属性名以服务端 schema 为准，此字段被忽略", example = "name") String keyProp,
        @Schema(description = "批次来源文件（可选）：原始上传文件名（LLM 侧 originName，非 mdName）；"
                + "服务端注入每个节点属性 _sourceFile，供 wiki 页「相关文档」反向溯源",
                example = "三星堆陨铁器分析报告.docx") String sourceFile,
        @Schema(description = "节点属性数组：每条必须含该类 keyProp 的值（缺失整批失败 400）",
                example = "[{\"name\": \"五代十国\"}, {\"name\": \"明永乐\"}]")
        @NotEmpty List<Map<String, Object>> items) {
}
