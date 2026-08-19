package com.conca.ontology.model;

/** 问询记录条目：LLM 的提问 + 用户的回答（用户画像 / ontology 画像 / 要解决的问题） */
public record ProfileEntry(
        String phase,
        String key,
        String question,
        String answer,
        String updatedAt) {
}
