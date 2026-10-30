package com.conca.ontology.user.api;

import java.util.Map;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.conca.ontology.user.web.SessionUser;
import com.conca.ontology.shared.web.ApiResponse;
import com.conca.ontology.user.dto.InitDTO;
import com.conca.ontology.user.dto.LoginDTO;
import com.conca.ontology.user.dto.UserVO;
import com.conca.ontology.user.service.UserService;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;

/**
 * 认证入口（拦截器放行 /api/session、/api/setup）：
 * 会话资源（POST 登录 / DELETE 登出 / GET 当前用户）+ 一次性系统初始化。
 * 注册与自助改密在 UserController（POST /api/users、PUT /api/users/me/password）。
 *
 * @author tianbaoxing
 * @date 2026-08-21
 */
@RestController
@RequestMapping("/api")
public class AuthController {

    private final UserService users;

    public AuthController(UserService users) {
        this.users = users;
    }

    /** 创建会话（登录） */
    @PostMapping("/session")
    public ApiResponse<Map<String, Object>> login(@RequestBody @Valid LoginDTO req, HttpSession session) {
        UserVO u = users.login(req.username(), req.password());
        session.setAttribute(SessionUser.SESSION_KEY, SessionUser.from(u));
        return ApiResponse.ok(userInfo(u));
    }

    /** 销毁会话（登出） */
    @DeleteMapping("/session")
    public ApiResponse<Map<String, Object>> logout(HttpSession session) {
        if (session != null) {
            session.invalidate();
        }
        return ApiResponse.ok(Map.of("logout", true));
    }

    /** 当前会话用户（未登录 data 为 null，前端据此跳转登录页） */
    @GetMapping("/session")
    public ApiResponse<Map<String, Object>> me(HttpSession session) {
        SessionUser u = SessionUser.of(session);
        if (u == null) {
            return ApiResponse.ok(null);
        }
        return ApiResponse.ok(Map.of("userId", u.userId(), "username", u.username(), "role", u.role()));
    }

    /** 系统初始化：设置 admin 密码并直接登录（仅当不存在任何 ADMIN 时可用） */
    @PostMapping("/setup")
    public ApiResponse<Map<String, Object>> init(@RequestBody @Valid InitDTO req, HttpSession session) {
        UserVO admin = users.initAdmin(req.password());
        session.setAttribute(SessionUser.SESSION_KEY, SessionUser.from(admin));
        return ApiResponse.ok(userInfo(admin));
    }

    private static Map<String, Object> userInfo(UserVO u) {
        return Map.of("userId", u.userId(), "username", u.username(), "role", u.role());
    }
}
