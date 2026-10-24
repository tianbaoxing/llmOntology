-- Wiki 手工元数据：data/wiki-metadata.json → 多行表（去全局 version，文件时代遗留概念）
-- 每个 MD 文件一行，以 md_name 为唯一 key 关联 files-md/ 转换产物（UTF-8 文件名，utf8mb4_bin 精确对齐 Java equals）
-- category/tags 为手工维护（NULL = 无，置空即清除）；MD 删除无联动通知，孤儿行由 WikiService 列表读时对账剔除

CREATE TABLE wiki_metadata (
    md_name    VARCHAR(512) NOT NULL COMMENT 'MD 文件名（唯一 key，可含子目录，/ 分隔，对齐 file_metadata.md_name）',
    category   VARCHAR(80)  NULL COMMENT '手工分类（NULL = 无，写路径清洗限长 80）',
    tags       JSON         NULL COMMENT '手工标签列表（NULL = 无，写路径空列表归一 NULL）',
    updated_at DATETIME     NOT NULL COMMENT '行级写入时间（API 不输出）',
    PRIMARY KEY (md_name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin COMMENT = 'Wiki 手工元数据（分类/标签，每 MD 一行）';
