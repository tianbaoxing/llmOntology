package com.conca.ontology.user.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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

import com.conca.ontology.user.web.SessionUser;
import com.conca.ontology.shared.web.ApiResponse;
import com.conca.ontology.user.dto.PromptSaveDTO;
import com.conca.ontology.user.dto.PromptToSkillDTO;
import com.conca.ontology.shared.web.ApiException;
import com.conca.ontology.user.model.UserPromptEntry;
import com.conca.ontology.user.service.UserPromptService;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;

/**
 * 用户提示词管理（chat 页「#」快捷菜单与管理弹窗的数据源）：
 * GET    /api/prompts                → 当前用户提示词列表（updatedAt 倒序，附带 skillNames 已转标记）
 * POST   /api/prompts                → 新增 {title, content}
 * PUT    /api/prompts/{id}            → 修改 {title, content}
 * DELETE /api/prompts/{id}            → 删除
 * POST   /api/prompts/{id}/to-skill   → 沉淀为 skill（写 skills/{name}/，新会话即生效）
 * DELETE /api/prompts/skills/{name}   → 删除本人转换的 skill（系统 skill 拒绝）
 * 提示词按登录用户隔离：userId 一律取当前会话，不信任请求参数。
 *
 * @author tianbaoxing
 * @date 2026-09-14
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
        String userId = currentUserId(session);
        List<UserPromptEntry> prompts = userPromptService.list(userId);
        Map<String, List<String>> skillIdx = userPromptService.skillIndex(userId);
        List<Map<String, Object>> rows = new ArrayList<>(prompts.size());
        for (UserPromptEntry e : prompts) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", e.id());
            row.put("title", e.title());
            row.put("content", e.content());
            row.put("createdAt", e.createdAt());
            row.put("updatedAt", e.updatedAt());
            row.put("skillNames", skillIdx.getOrDefault(e.id(), List.of()));
            rows.add(row);
        }
        return ApiResponse.ok(Map.of("prompts", rows));
    }

    @PostMapping
    public ApiResponse<UserPromptEntry> create(@Valid @RequestBody PromptSaveDTO request, HttpSession session) {
        return ApiResponse.ok(userPromptService.create(currentUserId(session), request.title(), request.content()));
    }

    @PutMapping("/{id}")
    public ApiResponse<UserPromptEntry> update(@PathVariable String id,
            @Valid @RequestBody PromptSaveDTO request, HttpSession session) {
        return ApiResponse.ok(userPromptService.update(currentUserId(session), id, request.title(), request.content()));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Map<String, Object>> delete(@PathVariable String id, HttpSession session) {
        userPromptService.delete(currentUserId(session), id);
        return ApiResponse.ok(Map.of("deleted", true));
    }

    /** 提示词沉淀为 skill：写 skills/{name}/，HarnessSkillMiddleware 动态扫描，新会话即生效 */
    @PostMapping("/{id}/to-skill")
    public ApiResponse<Map<String, Object>> toSkill(@PathVariable String id,
            @Valid @RequestBody(required = false) PromptToSkillDTO request, HttpSession session) {
        String userId = currentUserId(session);
        String name = request == null ? null : request.name();
        String description = request == null ? null : request.description();
        return ApiResponse.ok(userPromptService.toSkill(userId, id, name, description));
    }

    /** 删除本人转换的提示词 skill（prompt-meta.json owner 校验；系统 skill 拒绝） */
    @DeleteMapping("/skills/{name}")
    public ApiResponse<Map<String, Object>> deleteSkill(@PathVariable String name, HttpSession session) {
        userPromptService.deleteSkill(currentUserId(session), name);
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
