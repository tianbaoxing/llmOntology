package com.conca.ontology.user.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * 登录请求
 *
 * @author tianbaoxing
 * @date 2026-08-21
 */
public record LoginRequest(
        @NotBlank(message = "用户名不能为空") String username,
        @NotBlank(message = "密码不能为空") String password) {
}
