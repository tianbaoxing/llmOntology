-- P6 评测（一）：data/eval/custom-graders.json → custom_grader 多行表（每评测器一行）
-- 弃文档初稿「definition JSON 列」方案，扁平字段逐列拆出（对齐 P3/P4/P5 拆列先例，无嵌套结构）
-- utf8mb4_bin 精确对齐 Java equals；created_at/updated_at 存 ISO-8601 原文保纳秒精度
--   （对齐 skill_analysis analyzed_at / agent_definition 先例，record 内为 String 原样透传，DATETIME 会截断）
-- created_at NULLable：validate 对新建记录不设 createdAt（null 语义保真，对齐 P4 hidden* 先例）；
--   updated_at 恒有值（validate 固定写 now()）故 NOT NULL
-- 查询路径：find 按 PK 单行；all 按名称字典序（注册表 TreeMap 同序，列表展示稳定）

CREATE TABLE custom_grader (
    name         VARCHAR(32)  NOT NULL COMMENT '评测器名（3-32 位小写字母/数字/下划线，主键）',
    description  TEXT         NOT NULL COMMENT '用途描述（页面悬停展示，可空串）',
    mode         VARCHAR(16)  NOT NULL COMMENT '提示词模式：criteria（评分标准）| template（完整模板）',
    prompt       TEXT         NOT NULL COMMENT '提示词（criteria=评分标准；template=含五占位符完整模板，≤4000 字符）',
    created_at   VARCHAR(64)      NULL COMMENT '创建时间 ISO-8601 原文（保纳秒；新建记录缺省 NULL）',
    updated_at   VARCHAR(64)  NOT NULL COMMENT '更新时间 ISO-8601 原文（保纳秒）',
    PRIMARY KEY (name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin
  COMMENT = '自定义 LLM 评测器定义（每评测器一行；源 data/eval/custom-graders.json）';
