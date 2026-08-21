package com.conca.ontology.controller.api;

import java.util.Map;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.conca.ontology.auth.SessionUser;
import com.conca.ontology.dto.ApiResponse;
import com.conca.ontology.dto.ResetPasswordRequest;
import com.conca.ontology.dto.UserStatusRequest;
import com.conca.ontology.service.UserService;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;

/**
 * 用户管理入口（拦截器保证仅 ADMIN 可达）：列表 / 审核 / 启用禁用 / 重置密码 / 删除。
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService users;

    public UserController(UserService users) {
        this.users = users;
    }

    /** 用户列表（?status=PENDING/ACTIVE/DISABLED 可选筛选） */
    @GetMapping
    public ApiResponse<Map<String, Object>> list(@RequestParam(required = false) String status) {
        return ApiResponse.ok(Map.of("users", users.list(status)));
    }

    /** 审核：PENDING → ACTIVE */
    @PostMapping("/{userId}/approve")
    public ApiResponse<Map<String, Object>> approve(@PathVariable String userId) {
        users.approve(userId);
        return ApiResponse.ok(Map.of("approved", userId));
    }

    /** 启用 / 禁用 */
    @PostMapping("/{userId}/status")
    public ApiResponse<Map<String, Object>> status(@PathVariable String userId,
                                                   @RequestBody @Valid UserStatusRequest req,
                                                   HttpSession session) {
        users.setStatus(userId, req.enabled(), operator(session));
        return ApiResponse.ok(Map.of("userId", userId, "enabled", req.enabled()));
    }

    /** 重置指定用户密码 */
    @PostMapping("/{userId}/reset-password")
    public ApiResponse<Map<String, Object>> resetPassword(@PathVariable String userId,
                                                          @RequestBody @Valid ResetPasswordRequest req) {
        users.resetPassword(userId, req.newPassword());
        return ApiResponse.ok(Map.of("reset", userId));
    }

    /** 删除用户 */
    @DeleteMapping("/{userId}")
    public ApiResponse<Map<String, Object>> delete(@PathVariable String userId, HttpSession session) {
        users.delete(userId, operator(session));
        return ApiResponse.ok(Map.of("deleted", userId));
    }

    /** 拦截器已保证 /api/users 仅 ADMIN 且已登录可达 */
    private static SessionUser operator(HttpSession session) {
        return SessionUser.of(session);
    }
}
