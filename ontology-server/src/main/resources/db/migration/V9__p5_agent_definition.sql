-- P5 智能体定义：data/agents/{id}/agent.json → 多行表（每智能体一行，无全局 version；
--   行内 version 为每智能体独立定义版本，编辑时 +1，非其他期的"文件全局版本"，保留）
-- workspace/、state/ 目录结构保留文件系统（框架仍用），仅定义本体入库
-- utf8mb4_bin 精确对齐 Java equals；created_at/updated_at 存 ISO-8601 原文保纳秒精度
--   （对齐 skill_analysis analyzed_at 先例，record 内为 String 原样透传，DATETIME 会截断）
-- 五个清单列为类型化 JSON NOT NULL（保序；空即 []）；model 嵌套对象拆三列（仅三个字符串字段）
-- idx_updated_at 支撑管理列表「更新时间倒序」（同偏移定长 ISO，字典序 = 时间序）

CREATE TABLE agent_definition (
    agent_id            VARCHAR(40)    NOT NULL COMMENT '智能体 ID（目录名，如 agent-5f71c5b9，主键）',
    name                VARCHAR(30)    NOT NULL COMMENT '名称（1-30 字符）',
    description         VARCHAR(200)   NOT NULL COMMENT '一句话描述（≤200 字符）',
    sys_prompt          VARCHAR(8000)  NOT NULL COMMENT '系统提示词（1-8000 字符）',
    greeting             VARCHAR(500)   NOT NULL COMMENT '开场白（≤500 字符）',
    model_base_url      VARCHAR(255)   NOT NULL COMMENT '模型地址（空=平台默认）',
    model_name          VARCHAR(255)   NOT NULL COMMENT '模型名（空=平台默认）',
    model_api_key_ref   VARCHAR(255)   NOT NULL COMMENT '密钥引用（运行时经环境变量 AGENT_API_KEY 注入）',
    max_iters           INT            NOT NULL COMMENT '推理轮次上限（0-20，0=默认）',
    skills              JSON           NOT NULL COMMENT '平台技能清单（保序）',
    tools               JSON           NOT NULL COMMENT '内置工具白名单（保序）',
    http_tools          JSON           NOT NULL COMMENT 'HTTP 工具定义数组（保序）',
    mcps                JSON           NOT NULL COMMENT 'MCP 端点定义数组（保序）',
    subagents           JSON           NOT NULL COMMENT '子智能体定义数组（保序，≤3）',
    project_name        VARCHAR(50)    NOT NULL COMMENT '导出工程名（仅英文字母）',
    package_name        VARCHAR(100)   NOT NULL COMMENT 'Java 包名',
    status              VARCHAR(10)    NOT NULL COMMENT '状态：test（默认，不出现在 /chat）/ active（上架）',
    owner               VARCHAR(64)    NOT NULL COMMENT '所有者（创建者用户名）',
    version             INT            NOT NULL COMMENT '定义版本（每次编辑 +1，驱逐实例重建生效）',
    min_harness_version VARCHAR(32)    NOT NULL COMMENT '最低 harness 版本',
    created_at          VARCHAR(64)    NOT NULL COMMENT '创建时间 ISO-8601 原文（保纳秒）',
    updated_at          VARCHAR(64)    NOT NULL COMMENT '更新时间 ISO-8601 原文（保纳秒，列表按此倒序）',
    PRIMARY KEY (agent_id),
    KEY idx_updated_at (updated_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin
  COMMENT = '智能体定义（每智能体一行；源 data/agents/{id}/agent.json，workspace/state 留在文件系统）';
