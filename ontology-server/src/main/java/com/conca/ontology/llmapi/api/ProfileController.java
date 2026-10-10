package com.conca.ontology.llmapi.api;
import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.conca.ontology.shared.web.ApiResponse;
import com.conca.ontology.graph.dto.request.ProfileSaveRequest;
import com.conca.ontology.graph.dto.result.ProfileSaveResult;
import com.conca.ontology.graph.dto.view.ProfileView;
import com.conca.ontology.graph.service.ProfileService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "LLM-Profile", description = "构建期问询记录（用户画像 / ontology 画像）：LLM 在构建各阶段回看历史问答、追加新问答。"
        + "按 key upsert，同 key 重复问答覆盖，画像始终最新。")
@RestController
@RequestMapping("/api/llm")
public class ProfileController {

    private final ProfileService profileService;

    public ProfileController(ProfileService profileService) {
        this.profileService = profileService;
    }

    @Operation(summary = "全量问询记录",
            description = "读取全部问答条目（phase/key/question/answer/updatedAt，按首次创建顺序）。LLM 构建各阶段回看上下文用。"
                    + "无记录时返回空列表。")
    @GetMapping("/profile")
    public ApiResponse<ProfileView> profile() {
        return ApiResponse.ok(profileService.load());
    }

    @Operation(summary = "批量保存问答",
            description = "按 key upsert：同 key 覆盖已有条目（原位置，phase 缺省为 intake），新 key 追加，事务原子。"
                    + "返回保存后的全量条目总数。")
    @PostMapping("/profile")
    public ApiResponse<ProfileSaveResult> save(@Valid @RequestBody ProfileSaveRequest req) {
        return ApiResponse.ok(profileService.save(req));
    }
}
