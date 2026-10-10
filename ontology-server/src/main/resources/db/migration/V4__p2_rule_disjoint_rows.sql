-- P2 调整：rule_disjoint 由单行 rules JSON 列改为多行记录表（每条规则一行），去掉全局 version
-- （version 为文件时代遗留概念，多行存储下无意义；规则 ID（R001/R002...）为主键，字典序即展示序）
-- update_time 保留为行级写入时间（API 不输出，仅审计用途）

CREATE TABLE rule_disjoint_row (
    id          VARCHAR(16) NOT NULL COMMENT '规则 ID（R001/R002...，next-id 预填；字典序即展示序）',
    text        TEXT        NOT NULL COMMENT '规则文本（【】包裹的候选项至少 2 个）',
    update_time DATETIME    NOT NULL COMMENT '该行最后写入时间',
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin COMMENT = '互斥约束词典（每条规则一行）';

-- 存量数据搬迁：单行 rules JSON 数组逐条拆行
INSERT INTO rule_disjoint_row (id, text, update_time)
SELECT jt.rid, jt.rtext, rule_disjoint.update_time
FROM rule_disjoint,
     JSON_TABLE(rule_disjoint.rules, '$[*]' COLUMNS (
         rid   VARCHAR(16) PATH '$.id',
         rtext TEXT        PATH '$.text'
     )) AS jt;

DROP TABLE rule_disjoint;
RENAME TABLE rule_disjoint_row TO rule_disjoint;
