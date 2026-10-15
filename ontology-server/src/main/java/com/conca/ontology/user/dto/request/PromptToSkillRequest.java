package com.conca.ontology.user.dto.request;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 用户提示词转 skill 请求（POST /api/prompts/{id}/to-skill）。
 * name/description 均可缺省：name 缺省自动生成 user-xxxxxx；description 缺省按模板生成。
 *
 * @author tianbaoxing
 * @date 2026-09-15
 */
public record PromptToSkillRequest(
        @Size(max = 64, message = "skill 名最长 64 字符")
        @Pattern(regexp = "^[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?$",
                message = "skill 名只能包含小写字母、数字、连字符，且不能以连字符开头或结尾")
        String name,

        @Size(max = 1024, message = "description 最长 1024 字")
        String description) {
}
