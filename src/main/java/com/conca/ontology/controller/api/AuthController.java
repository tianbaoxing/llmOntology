package com.conca.ontology.controller.api;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.conca.ontology.auth.SessionUser;
import com.conca.ontology.dto.ApiResponse;
import com.conca.ontology.dto.InitRequest;
import com.conca.ontology.dto.LoginRequest;
import com.conca.ontology.dto.PasswordChangeRequest;
import com.conca.ontology.dto.RegisterRequest;
import com.conca.ontology.exception.ApiException;
import com.conca.ontology.model.UserEntry;
import com.conca.ontology.service.UserService;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;

/**
 * 认证入口（拦截器放行 /api/auth/**，敏感操作在方法内自行校验会话）：
 * 初始化向导（status/init）、登录登出（login/logout/me）、自助注册（register）、修改自己密码（password）。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService users;

    public AuthController(UserService users) {
        this.users = users;
    }

    /** 初始化状态（登录页据此切换"系统初始化 / 登录"模式） */
    @GetMapping("/status")
    public ApiResponse<Map<String, Object>> status() {
        return ApiResponse.ok(Map.of("initialized", users.initialized()));
    }

    /** 系统初始化：设置 admin 密码并直接登录（仅当不存在任何 ADMIN 时可用） */
    @PostMapping("/init")
    public ApiResponse<Map<String, Object>> init(@RequestBody @Valid InitRequest req, HttpSession session) {
        UserEntry admin = users.initAdmin(req.password());
        session.setAttribute(SessionUser.SESSION_KEY, SessionUser.from(admin));
        return ApiResponse.ok(userInfo(admin));
    }

    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(@RequestBody @Valid LoginRequest req, HttpSession session) {
        UserEntry u = users.login(req.username(), req.password());
        session.setAttribute(SessionUser.SESSION_KEY, SessionUser.from(u));
        return ApiResponse.ok(userInfo(u));
    }

    @PostMapping("/logout")
    public ApiResponse<Map<String, Object>> logout(HttpSession session) {
        if (session != null) {
            session.invalidate();
        }
        return ApiResponse.ok(Map.of("logout", true));
    }

    /** 当前用户（未登录 data 为 null，前端据此跳转登录页） */
    @GetMapping("/me")
    public ApiResponse<Map<String, Object>> me(HttpSession session) {
        SessionUser u = SessionUser.of(session);
        if (u == null) {
            return ApiResponse.ok(null);
        }
        return ApiResponse.ok(Map.of("userId", u.userId(), "username", u.username(), "role", u.role()));
    }

    /** 自助注册 → PENDING，需管理员审核 */
    @PostMapping("/register")
    public ApiResponse<Map<String, Object>> register(@RequestBody @Valid RegisterRequest req) {
        UserEntry u = users.register(req.username(), req.password());
        return ApiResponse.ok(Map.of("username", u.username(), "status", u.status()));
    }

    /** 修改自己密码 */
    @PostMapping("/password")
    public ApiResponse<Map<String, Object>> password(@RequestBody @Valid PasswordChangeRequest req,
                                                     HttpSession session) {
        SessionUser u = SessionUser.of(session);
        if (u == null) {
            throw new ApiException(401, "未登录或会话已过期");
        }
        users.changePassword(u.userId(), req.oldPassword(), req.newPassword());
        return ApiResponse.ok(Map.of("changed", true));
    }

    private static Map<String, Object> userInfo(UserEntry u) {
        return Map.of("userId", u.userId(), "username", u.username(), "role", u.role());
    }
}
