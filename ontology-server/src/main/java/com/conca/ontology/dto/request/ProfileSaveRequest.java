package com.conca.ontology.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/** 问询记录批量保存请求（LLM 提问 → 用户回答，按 key upsert） */
@Schema(description = "问询记录批量保存请求（按 key upsert：同 key 覆盖）")
public record ProfileSaveRequest(
        @Schema(description = "问答条目数组") @NotEmpty List<@Valid Item> items) {

    @Schema(description = "单条问答")
    public record Item(
            @Schema(description = "构建阶段标识（缺省为 intake）", example = "intake") String phase,
            @Schema(description = "条目唯一键（同 key 覆盖旧条目）", example = "user.goal") @NotBlank String key,
            @Schema(description = "LLM 的提问内容") @NotBlank String question,
            @Schema(description = "用户的回答内容") @NotBlank String answer) {
    }
}
