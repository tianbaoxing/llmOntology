-- P3 内容安全 + 改写配置：JSON 文件 → 多行表（去全局 version，文件时代遗留概念）
-- rule_content_security：每条规则一行；allowlist 子清单历史已合并为规则行 allowlisted 字段，落行级标记列
-- ord 保留原文件声明顺序（扫描遍历序 = 展示序，新增规则追加尾部）

CREATE TABLE rule_content_security (
    id          VARCHAR(64)  NOT NULL COMMENT '规则 ID（唯一，如 instr-ignore-previous）',
    category    VARCHAR(32)  NOT NULL COMMENT '规则分类（instruction_override/role_switch/system_prompt_leak/data_exfiltration/tool_abuse/harmful_content）',
    severity    VARCHAR(16)  NOT NULL COMMENT 'INFO | LOW | MEDIUM | HIGH | CRITICAL',
    action      VARCHAR(16)  NOT NULL COMMENT 'ALLOW | REVIEW | BLOCK',
    description VARCHAR(512) NOT NULL COMMENT '规则描述',
    pattern     TEXT         NOT NULL COMMENT '命中正则（加载时大小写不敏感预编译）',
    allowlisted TINYINT      NOT NULL DEFAULT 0 COMMENT 'allowlist 标记（1=已加入，命中后不计入风险）',
    ord         INT          NOT NULL COMMENT '展示/扫描顺序（原文件声明顺序，新增规则追加尾部）',
    updated_at  DATETIME     NOT NULL COMMENT '行级写入时间（API 不输出）',
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin COMMENT = '内容安全规则（每条一行）';

-- rewrite_config：4 项功能开关各一行（semantic/typo/term/context）

CREATE TABLE rewrite_config (
    feature    VARCHAR(16) NOT NULL COMMENT '功能类型：semantic | typo | term | context',
    enabled    TINYINT     NOT NULL COMMENT '是否启用（1=启用）',
    prompt     TEXT        NOT NULL COMMENT '功能提示词（页面可改，保存后热生效）',
    updated_at DATETIME    NOT NULL COMMENT '行级写入时间（API 不输出）',
    PRIMARY KEY (feature)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin COMMENT = '语义改写配置（每功能一行）';
