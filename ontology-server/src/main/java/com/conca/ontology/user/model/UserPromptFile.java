package com.conca.ontology.user.model;

import java.util.List;

/** data/prompts/{userId}.json 文件结构（version + tmp 原子写） */
public record UserPromptFile(int version, List<UserPromptEntry> items) {

    public static UserPromptFile empty() {
        return new UserPromptFile(0, List.of());
    }
}
