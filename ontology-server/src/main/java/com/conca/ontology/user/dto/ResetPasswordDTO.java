package com.conca.ontology.user.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 用户管理：管理员重置指定用户密码
 *
 * @author tianbaoxing
 * @date 2026-08-21
 */
public record ResetPasswordDTO(
        @NotBlank(message = "新密码不能为空") String newPassword) {
}
