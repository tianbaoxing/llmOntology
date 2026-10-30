package com.conca.ontology.user.dto;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import com.conca.ontology.user.entity.UserPromptEntity;

/**
 * 用户提示词出参 VO（chat 页「#」菜单与管理弹窗数据源）。
 * 时间字段保持 "yyyy-MM-dd HH:mm:ss" 字符串格式（前端零感知）；
 * userId 为行级隔离字段，不出 API（由 Controller 从登录态传入 Service）。
 *
 * @author tianbaoxing
 * @date 2026-10-30
 */
public record UserPromptVO(
        String id,
        String title,
        String content,
        String createdAt,
        String updatedAt) {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static UserPromptVO from(UserPromptEntity e) {
        return new UserPromptVO(e.getId(), e.getTitle(), e.getContent(),
                format(e.getCreatedAt()), format(e.getUpdatedAt()));
    }

    private static String format(LocalDateTime t) {
        return t == null ? null : TIME_FMT.format(t);
    }
}
