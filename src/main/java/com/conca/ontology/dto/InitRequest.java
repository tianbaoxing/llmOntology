package com.conca.ontology.dto;

import jakarta.validation.constraints.NotBlank;

/** 系统初始化：设置 admin 密码 */
public record InitRequest(
        @NotBlank(message = "密码不能为空") String password) {
}
