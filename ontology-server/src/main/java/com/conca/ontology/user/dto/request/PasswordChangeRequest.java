package com.conca.ontology.user.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * 修改自己密码：验原密码
 *
 * @author tianbaoxing
 * @date 2026-08-21
 */
public record PasswordChangeRequest(
        @NotBlank(message = "原密码不能为空") String oldPassword,
        @NotBlank(message = "新密码不能为空") String newPassword) {
}
