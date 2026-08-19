package com.conca.ontology.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/** 问询记录批量保存请求（LLM 提问 → 用户回答，按 key upsert） */
public record ProfileSaveRequest(@NotEmpty List<@Valid Item> items) {

    public record Item(
            String phase,
            @NotBlank String key,
            @NotBlank String question,
            @NotBlank String answer) {
    }
}
