package com.conca.ontology.dto;

import java.util.List;

import com.conca.ontology.model.ProfileEntry;

import io.swagger.v3.oas.annotations.media.Schema;

/** 问询记录全量视图（用户画像 / ontology 画像） */
@Schema(description = "问询记录全量视图（用户画像 / ontology 画像）")
public record ProfileView(
        @Schema(description = "画像文件版本号（每次保存 +1，0=尚未创建）", example = "5") int version,
        @Schema(description = "问答条目列表（按 key 去重，始终最新）") List<ProfileEntry> items) {
}
