package com.conca.ontology.controller.api;

import java.util.Map;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.conca.ontology.auth.SessionUser;
import com.conca.ontology.dto.ApiResponse;
import com.conca.ontology.dto.request.PasswordChangeRequest;
import com.conca.ontology.dto.request.RegisterRequest;
import com.conca.ontology.dto.request.ResetPasswordRequest;
import com.conca.ontology.dto.request.UserPatchRequest;
import com.conca.ontology.exception.ApiException;
import com.conca.ontology.model.UserEntry;
import com.conca.ontology.service.UserService;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;

/**
 * 用户资源入口：
 * - 公开（拦截器放行）：POST /api/users（自助注册 → PENDING 待审核）
 * - 自助（方法内校验会话）：PUT /api/users/me/password（修改自己密码）
 * - 管理操作（拦截器保证仅 ADMIN 可达）：列表 / 状态变更（审核、启停）/ 重置密码 / 删除
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService users;

    public UserController(UserService users) {
        this.users = users;
    }

    /** 用户列表（?status=PENDING/ACTIVE/DISABLED 可选筛选，仅 ADMIN） */
    @GetMapping
    public ApiResponse<Map<String, Object>> list(@RequestParam(required = false) String status) {
        return ApiResponse.ok(Map.of("users", users.list(status)));
    }

    /** 自助注册 → PENDING，需管理员审核（公开接口） */
    @PostMapping
    public ApiResponse<Map<String, Object>> register(@RequestBody @Valid RegisterRequest req) {
        UserEntry u = users.register(req.username(), req.password());
        return ApiResponse.ok(Map.of("username", u.username(), "status", u.status()));
    }

    /** 修改自己密码（公开路由，方法内校验会话） */
    @PutMapping("/me/password")
    public ApiResponse<Map<String, Object>> changePassword(@RequestBody @Valid PasswordChangeRequest req,
                                                           HttpSession session) {
        SessionUser u = SessionUser.of(session);
        if (u == null) {
            throw new ApiException(401, "未登录或会话已过期");
        }
        users.changePassword(u.userId(), req.oldPassword(), req.newPassword());
        return ApiResponse.ok(Map.of("changed", true));
    }

    /**
     * 更新用户状态（仅 ADMIN）：body {"status":"APPROVED"|"ACTIVE"|"DISABLED"}。
     * APPROVED = 待审核用户通过审核；ACTIVE/DISABLED = 启用/禁用（含自保护校验）。
     */
    @PatchMapping("/{userId}")
    public ApiResponse<Map<String, Object>> update(@PathVariable String userId,
                                                   @RequestBody @Valid UserPatchRequest req,
                                                   HttpSession session) {
        String status = req.status().toUpperCase();
        if ("APPROVED".equals(status)) {
            users.approve(userId);
            status = "ACTIVE";
        } else {
            users.setStatus(userId, "ACTIVE".equals(status), operator(session));
        }
        return ApiResponse.ok(Map.of("userId", userId, "status", status));
    }

    /** 重置指定用户密码（仅 ADMIN） */
    @PostMapping("/{userId}/password")
    public ApiResponse<Map<String, Object>> resetPassword(@PathVariable String userId,
                                                          @RequestBody @Valid ResetPasswordRequest req) {
        users.resetPassword(userId, req.newPassword());
        return ApiResponse.ok(Map.of("reset", userId));
    }

    /** 删除用户（仅 ADMIN） */
    @DeleteMapping("/{userId}")
    public ApiResponse<Map<String, Object>> delete(@PathVariable String userId, HttpSession session) {
        users.delete(userId, operator(session));
        return ApiResponse.ok(Map.of("deleted", userId));
    }

    /** 拦截器已保证管理操作仅 ADMIN 且已登录可达 */
    private static SessionUser operator(HttpSession session) {
        return SessionUser.of(session);
    }
}
