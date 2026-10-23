package com.conca.ontology.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * PATCH /api/users/{userId} 请求体：
 * status = APPROVED（待审核用户通过审核）/ ACTIVE（启用）/ DISABLED（禁用）。
 *
 * @author tianbaoxing
 * @date 2026-08-21
 */
public record UserPatchDTO(
        @NotBlank(message = "status 不能为空")
        @Pattern(regexp = "(?i)APPROVED|ACTIVE|DISABLED", message = "status 仅支持 APPROVED/ACTIVE/DISABLED")
        String status) {
}
