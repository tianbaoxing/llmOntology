package com.conca.ontology.dto;

import jakarta.validation.constraints.NotNull;

/** 用户管理：启用 / 禁用 */
public record UserStatusRequest(
        @NotNull(message = "enabled 不能为空") Boolean enabled) {
}
