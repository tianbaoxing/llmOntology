-- P4 文件元数据：data/file-metadata.json → 多行表（去全局 version，文件时代遗留概念）
-- 每个文件一行；自增 id 兼作列表顺序（同 name 覆盖保留原位置，新文件追加尾部，对齐原 LinkedHashMap 语义）
-- name 为相对 upload-dir 的文件名（可含子目录，'/' 分隔），utf8mb4_bin 精确对齐 Java equals
-- 转换四列可空：NULL 表示待对账回填（对应旧 JSON 字段缺省），NONE 表示文本类无需转换

CREATE TABLE file_metadata (
    id             BIGINT        NOT NULL AUTO_INCREMENT COMMENT '自增序（兼作列表顺序）',
    name           VARCHAR(512)  NOT NULL COMMENT '文件名（相对 upload-dir，/ 分隔，可含子目录）',
    path           VARCHAR(1024) NOT NULL COMMENT '绝对路径（LLM 按此路径读取文件内容）',
    size           BIGINT        NOT NULL COMMENT '文件大小（字节）',
    ext            VARCHAR(32)   NOT NULL COMMENT '扩展名（小写，无点）',
    description    TEXT          NOT NULL COMMENT '文件说明（可为空字符串）',
    uploaded_at    DATETIME      NOT NULL COMMENT '上传时间',
    md_name        VARCHAR(512)  NULL COMMENT '对应 Markdown 文件名（仅二进制转换文件有值）',
    convert_status VARCHAR(16)   NULL COMMENT '转换状态：NONE（文本类）/ SUCCESS / FAILED（NULL=待对账回填）',
    convert_msg    TEXT          NULL COMMENT '转换失败原因（仅 FAILED 时有值）',
    converted_at   DATETIME      NULL COMMENT '转换完成时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_name (name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin COMMENT = '上传文件元数据（每文件一行）';
