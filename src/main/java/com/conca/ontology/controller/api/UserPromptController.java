package com.conca.ontology.controller.api;

import java.util.List;
import java.util.Map;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.conca.ontology.auth.SessionUser;
import com.conca.ontology.dto.ApiResponse;
import com.conca.ontology.dto.request.PromptSaveRequest;
import com.conca.ontology.exception.ApiException;
import com.conca.ontology.model.UserPromptEntry;
import com.conca.ontology.service.UserPromptService;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;

/**
 * 用户提示词管理（chat 页「#」快捷菜单与管理弹窗的数据源）：
 * GET    /api/prompts        → 当前用户提示词列表（updatedAt 倒序）
 * POST   /api/prompts        → 新增 {title, content}
 * PUT    /api/prompts/{id}    → 修改 {title, content}
 * DELETE /api/prompts/{id}    → 删除
 * 提示词按登录用户隔离：userId 一律取当前会话，不信任请求参数。
 */
@RestController
@RequestMapping("/api/prompts")
@Validated
public class UserPromptController {

    private final UserPromptService userPromptService;

    public UserPromptController(UserPromptService userPromptService) {
        this.userPromptService = userPromptService;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> list(HttpSession session) {
        return ApiResponse.ok(Map.of("prompts", userPromptService.list(currentUserId(session))));
    }

    @PostMapping
    public ApiResponse<UserPromptEntry> create(@Valid @RequestBody PromptSaveRequest request, HttpSession session) {
        return ApiResponse.ok(userPromptService.create(currentUserId(session), request.title(), request.content()));
    }

    @PutMapping("/{id}")
    public ApiResponse<UserPromptEntry> update(@PathVariable String id,
            @Valid @RequestBody PromptSaveRequest request, HttpSession session) {
        return ApiResponse.ok(userPromptService.update(currentUserId(session), id, request.title(), request.content()));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Map<String, Object>> delete(@PathVariable String id, HttpSession session) {
        userPromptService.delete(currentUserId(session), id);
        return ApiResponse.ok(Map.of("deleted", true));
    }

    /** 拦截器已保证登录；此处兜底防御性校验 */
    private static String currentUserId(HttpSession session) {
        SessionUser u = SessionUser.of(session);
        if (u == null) {
            throw new ApiException(401, "未登录或会话已过期");
        }
        return u.userId();
    }
}
