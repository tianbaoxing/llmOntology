package com.conca.ontology.user.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 系统初始化：设置 admin 密码
 *
 * @author tianbaoxing
 * @date 2026-08-21
 */
public record InitDTO(
        @NotBlank(message = "密码不能为空") String password) {
}
