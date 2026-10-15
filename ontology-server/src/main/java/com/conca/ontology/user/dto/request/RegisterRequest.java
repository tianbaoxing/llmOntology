package com.conca.ontology.user.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * 自助注册请求（注册后待管理员审核）
 *
 * @author tianbaoxing
 * @date 2026-08-21
 */
public record RegisterRequest(
        @NotBlank(message = "用户名不能为空") String username,
        @NotBlank(message = "密码不能为空") String password) {
}
