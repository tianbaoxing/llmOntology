package com.conca.ontology.user.model;

/**
 * 用户提示词条目：data/prompts/{userId}.json 的单元（id 主键；title 供 # 菜单展示与过滤）
 *
 * @author tianbaoxing
 * @date 2026-09-14
 */
public record UserPromptEntry(
        String id,
        String title,
        String content,
        String createdAt,
        String updatedAt) {
}
