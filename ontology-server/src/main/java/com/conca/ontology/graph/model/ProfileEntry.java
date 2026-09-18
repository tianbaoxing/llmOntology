package com.conca.ontology.graph.model;
import io.swagger.v3.oas.annotations.media.Schema;

/** 问询记录条目：LLM 的提问 + 用户的回答（用户画像 / ontology 画像 / 要解决的问题） */
@Schema(description = "问询记录条目：LLM 的提问 + 用户的回答")
public record ProfileEntry(
        @Schema(description = "构建阶段标识", example = "intake") String phase,
        @Schema(description = "条目唯一键", example = "user.goal") String key,
        @Schema(description = "LLM 的提问内容") String question,
        @Schema(description = "用户的回答内容") String answer,
        @Schema(description = "最近更新时间（ISO-8601）", example = "2026-08-21T15:30:00.123") String updatedAt) {
}
