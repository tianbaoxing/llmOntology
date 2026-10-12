-- P5 用户提示词：data/prompts/{userId}.json（按用户一文件）→ 多行表（去全局 version，仅在旧文件写盘时回填、从未被读取）
-- 每条提示词一行；id 为源 JSON items[].id 的 UUID（IdType.INPUT 原值迁移）；
-- user_id 来自源文件名（{userId}.json），utf8mb4_bin 精确对齐 Java equals
-- idx_user_updated 覆盖唯一高频查询：WHERE user_id ORDER BY updated_at DESC
-- 时间列 DATETIME：源格式 "yyyy-MM-dd HH:mm:ss"（秒级）无损直存，API 输出格式不变（前端零感知）

CREATE TABLE user_prompt (
    id         VARCHAR(36) NOT NULL COMMENT '提示词主键（UUID，源 JSON items[].id）',
    user_id    VARCHAR(32) NOT NULL COMMENT '所属用户（源文件名 {userId}.json）',
    title      VARCHAR(50) NOT NULL COMMENT '标题（chat 页 # 菜单展示与过滤）',
    content    TEXT        NOT NULL COMMENT '提示词正文',
    created_at DATETIME    NOT NULL COMMENT '创建时间',
    updated_at DATETIME    NOT NULL COMMENT '更新时间（列表倒序依据）',
    PRIMARY KEY (id),
    KEY idx_user_updated (user_id, updated_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin COMMENT = '用户提示词（每条一行，P5 迁移自 data/prompts/*.json）';
