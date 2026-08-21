package com.conca.ontology.dto;

import jakarta.validation.constraints.NotBlank;

/** 自助注册请求（注册后待管理员审核） */
public record RegisterRequest(
        @NotBlank(message = "用户名不能为空") String username,
        @NotBlank(message = "密码不能为空") String password) {
}
