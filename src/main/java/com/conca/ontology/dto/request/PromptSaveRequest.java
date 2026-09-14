package com.conca.ontology.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 用户提示词新增/修改请求（POST /api/prompts、PUT /api/prompts/{id}）。
 */
public record PromptSaveRequest(
        @NotBlank(message = "标题不能为空")
        @Size(max = 50, message = "标题最长 50 字")
        String title,

        @NotBlank(message = "内容不能为空")
        @Size(max = 5000, message = "内容最长 5000 字")
        String content) {
}
