package com.conca.ontology.user.model;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import com.conca.ontology.user.entity.UserPromptEntity;

/**
 * {@link UserPromptEntity} ↔ {@link UserPromptEntry} 转换器。
 * 对外 API 模型 UserPromptEntry 的时间字段保持 "yyyy-MM-dd HH:mm:ss" 字符串格式（前端零感知），
 * 库内实体使用 LocalDateTime（DATETIME 列）。
 * userId 为行级新增字段（原按用户一文件的文件名），API 模型不含，由调用方（Service）按行设置。
 */
public final class UserPromptEntryConverter {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private UserPromptEntryConverter() {
    }

    public static UserPromptEntry toEntry(UserPromptEntity e) {
        return new UserPromptEntry(e.getId(), e.getTitle(), e.getContent(),
                format(e.getCreatedAt()), format(e.getUpdatedAt()));
    }

    public static UserPromptEntity toEntity(UserPromptEntry p) {
        UserPromptEntity e = new UserPromptEntity();
        e.setId(p.id());
        e.setTitle(p.title());
        e.setContent(p.content());
        e.setCreatedAt(parse(p.createdAt()));
        e.setUpdatedAt(parse(p.updatedAt()));
        return e;
    }

    private static String format(LocalDateTime t) {
        return t == null ? null : TIME_FMT.format(t);
    }

    private static LocalDateTime parse(String s) {
        return s == null || s.isBlank() ? null : LocalDateTime.parse(s, TIME_FMT);
    }
}
