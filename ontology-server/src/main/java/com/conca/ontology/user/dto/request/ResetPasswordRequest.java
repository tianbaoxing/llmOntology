package com.conca.ontology.user.dto.request;

import jakarta.validation.constraints.NotBlank;

/** 用户管理：管理员重置指定用户密码 */
public record ResetPasswordRequest(
        @NotBlank(message = "新密码不能为空") String newPassword) {
}
