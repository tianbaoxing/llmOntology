package com.conca.ontology.controller.llmapi;

import java.util.Map;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.conca.ontology.dto.ApiResponse;
import com.conca.ontology.dto.ProfileSaveRequest;
import com.conca.ontology.service.ProfileService;

/** LLM 专属入口：构建期问询记录（用户画像 / ontology 画像）的读写。 */
@RestController
@RequestMapping("/api/llm")
public class ProfileController {

    private final ProfileService profileService;

    public ProfileController(ProfileService profileService) {
        this.profileService = profileService;
    }

    /** 全量问询记录（LLM 构建各阶段回看上下文用） */
    @GetMapping("/profile")
    public ApiResponse<Map<String, Object>> profile() {
        return ApiResponse.ok(profileService.load());
    }

    /** 批量保存问答（按 key upsert：同 key 覆盖，画像始终最新） */
    @PostMapping("/profile")
    public ApiResponse<Map<String, Object>> save(@Valid @RequestBody ProfileSaveRequest req) {
        return ApiResponse.ok(profileService.save(req));
    }
}
