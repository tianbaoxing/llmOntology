-- P2 调整：ontology_profile 由单行 items JSON 列改为多行记录表（每条问答一行），去掉全局 version
-- （version 为文件时代遗留概念，多行存储下无意义；key 是 MySQL 保留字，列名用 item_key）
-- updated_at 存 ISO-8601 原文（VARCHAR）：源数据含纳秒精度（9 位小数），DATETIME(6) 会截断导致 API 输出漂移
-- 自增 id 兼作顺序列：同 key 覆盖保留原位置（updateById 不动 id），新 key 追加在尾部

CREATE TABLE ontology_profile_row (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增序（兼作条目顺序）',
    phase       VARCHAR(32)  NOT NULL COMMENT '构建阶段标识（intake/concept/instance/validate 等）',
    item_key    VARCHAR(128) NOT NULL COMMENT '条目唯一键（同 key 覆盖，对齐 Java equals 语义的 utf8mb4_bin）',
    question    TEXT         NOT NULL COMMENT 'LLM 的提问内容',
    answer      TEXT         NOT NULL COMMENT '用户的回答内容',
    updated_at  VARCHAR(64)  NOT NULL COMMENT '最近更新时间（ISO-8601 原文，保留纳秒精度）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_item_key (item_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin COMMENT = '构建期问询记录（每条问答一行）';

-- 存量数据搬迁：单行 items JSON 数组按原顺序拆为多行
INSERT INTO ontology_profile_row (phase, item_key, question, answer, updated_at)
SELECT jt.phase, jt.item_key, jt.question, jt.answer, jt.updated_at
FROM ontology_profile,
     JSON_TABLE(ontology_profile.items, '$[*]' COLUMNS (
         ord      FOR ORDINALITY,
         phase    VARCHAR(32)  PATH '$.phase',
         item_key VARCHAR(128) PATH '$.key',
         question TEXT         PATH '$.question',
         answer   TEXT         PATH '$.answer',
         updated_at VARCHAR(64) PATH '$.updatedAt'
     )) AS jt
ORDER BY jt.ord;

DROP TABLE ontology_profile;
RENAME TABLE ontology_profile_row TO ontology_profile;
