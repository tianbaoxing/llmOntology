package com.conca.ontology.graph.dto.view;
import java.util.List;

import com.conca.ontology.graph.model.ProfileEntry;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 问询记录全量视图（用户画像 / ontology 画像）
 *
 * @author tianbaoxing
 * @date 2026-08-21
 */
@Schema(description = "问询记录全量视图（用户画像 / ontology 画像）")
public record ProfileView(
        @Schema(description = "问答条目列表（按 key 去重，始终最新；按首次创建顺序）") List<ProfileEntry> items) {
}
