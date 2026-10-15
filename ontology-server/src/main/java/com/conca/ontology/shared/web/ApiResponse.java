package com.conca.ontology.shared.web;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 统一响应信封
 *
 * @author tianbaoxing
 * @date 2026-08-19
 */
@Schema(description = "统一响应信封：code=0 成功；非 0 为错误码（与 HTTP 状态码语义一致），msg 为错误说明")
public record ApiResponse<T>(
        @Schema(description = "0=成功；400=参数错误；401=未登录；403=无权限；404=不存在；409=冲突；500=服务器错误",
                example = "0") int code,
        @Schema(description = "成功固定 ok；失败为可读错误信息", example = "ok") String msg,
        @Schema(description = "业务数据；失败时为 null") T data) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, "ok", data);
    }

    public static <T> ApiResponse<T> error(int code, String msg) {
        return new ApiResponse<>(code, msg, null);
    }
}
