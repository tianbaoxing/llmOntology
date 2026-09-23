package com.conca.ontology.wiki.dto.result;
import com.conca.ontology.wiki.dto.view.WikiDocView;

import io.swagger.v3.oas.annotations.media.Schema;

/** 编辑 Wiki 元信息结果：返回更新后的文档视图。 */
@Schema(description = "编辑 Wiki 元信息结果")
public record WikiMetaUpdateResult(
        @Schema(description = "元数据版本", example = "4") int version,
        @Schema(description = "更新后的文档视图") WikiDocView doc) {
}
