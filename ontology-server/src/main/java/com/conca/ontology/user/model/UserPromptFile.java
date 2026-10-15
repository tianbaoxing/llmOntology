package com.conca.ontology.user.model;

import java.util.List;

/**
 * data/prompts/{userId}.json 遗留文件结构（P5 前唯一事实源；现仅迁移器读取，顶层 version 弃用）
 *
 * @author tianbaoxing
 * @date 2026-09-14
 */
public record UserPromptFile(int version, List<UserPromptEntry> items) {
}
