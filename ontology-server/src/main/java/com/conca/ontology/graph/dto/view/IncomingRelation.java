package com.conca.ontology.graph.dto.view;
import io.swagger.v3.oas.annotations.media.Schema;

/** 类详情视图内的入边关系（由全量定义推导，非本类声明） */
@Schema(description = "入边关系（由全量定义推导：其他类声明的指向本类的关系）")
public record IncomingRelation(
        @Schema(description = "关系类型名", example = "OF_ERA") String name,
        @Schema(description = "中文名", example = "属于年代") String cnName,
        @Schema(description = "发起方类", example = "CulturalRelic") String fromClass,
        @Schema(description = "库中该类型关系的实时数量", example = "5016") long count) {
}
