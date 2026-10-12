-- P4 技能使用分析：data/skill-analysis.json → 多行表（去全局 version，仅在旧文件写盘时回填、从未被读取）
-- 每技能一行；skill_name 主键（SKILL.md frontmatter name），utf8mb4_bin 精确对齐 Java equals
-- analyzed_at / content_hash 可空：keyInfo-only 记录（仅保存识别关键词、尚未检测）二者为 NULL，
--   stale 判断以 content_hash 是否为空区分「未检测」；analyzed_at 存 ISO-8601 原文保纳秒精度（对齐 ontology_profile V3 先例）
-- 六个清单列 NOT NULL（记录不变式：检测与关键词数据恒为非空数组，空即 []）；
-- hidden_* 三个忽略清单可空：旧数据文件缺这三个字段时反序列化为 null，列保真存 NULL（round-trip 零失真）

CREATE TABLE skill_analysis (
    skill_name           VARCHAR(128) NOT NULL COMMENT '技能名（SKILL.md frontmatter name，主键）',
    analyzed_at          VARCHAR(64)  NULL COMMENT '分析时间 ISO-8601 原文（保纳秒；仅保存关键词未检测时 NULL）',
    content_hash         VARCHAR(64)  NULL COMMENT '扫描文件内容哈希 SHA-256（详情页对比判过期；未检测时 NULL）',
    scanned_files        JSON         NOT NULL COMMENT '参与扫描的文件相对路径数组（保序）',
    tools_used           JSON         NOT NULL COMMENT '被使用的系统工具数组 [{name,count,inCodeBlock}]（按名排序）',
    tools_not_used       JSON         NOT NULL COMMENT '未使用的系统工具名数组（按名排序）',
    apis                 JSON         NOT NULL COMMENT '提取到的 API 路径数组（去重保序）',
    shell_commands       JSON         NOT NULL COMMENT '提取到的 shell 命令数组 [{command,count}]（按命令排序）',
    key_info             JSON         NOT NULL COMMENT '用户填写的识别关键词数组（特征指纹，独立于检测结果保留）',
    hidden_tools          JSON         NULL COMMENT '用户删除（忽略）的工具检测项（跨重新检测保留；旧数据缺字段为 NULL）',
    hidden_apis           JSON         NULL COMMENT '用户删除（忽略）的 API 检测项（旧数据缺字段为 NULL）',
    hidden_shell_commands JSON         NULL COMMENT '用户删除（忽略）的 shell 命令检测项（旧数据缺字段为 NULL）',
    PRIMARY KEY (skill_name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin COMMENT = '技能使用分析结果（每技能一行）';
