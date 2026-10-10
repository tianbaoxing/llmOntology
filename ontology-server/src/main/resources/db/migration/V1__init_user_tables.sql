-- P1 用户系统：平台用户 + 角色权限黑名单
-- utf8mb4_bin（区分大小写）精确对齐 Java equals 语义：findByUserId 精确匹配；
-- 忽略大小写的查询（findByUsername / 注册查重）在 SQL 中显式 LOWER 双边处理

CREATE TABLE sys_user (
    user_id       VARCHAR(32)  NOT NULL COMMENT '用户唯一标识（终身不变，当前与用户名同值）',
    username      VARCHAR(32)  NOT NULL COMMENT '展示名',
    password_hash VARCHAR(128) NOT NULL COMMENT 'PBKDF2 自描述格式 pbkdf2-sha256$迭代数$盐hex$哈希hex',
    role          VARCHAR(16)  NOT NULL COMMENT 'ADMIN | USER',
    status        VARCHAR(16)  NOT NULL COMMENT 'PENDING | ACTIVE | DISABLED',
    created_at    DATETIME     NOT NULL COMMENT '创建时间',
    updated_at    DATETIME     NOT NULL COMMENT '最后更新时间',
    last_login_at DATETIME     NULL     COMMENT '最后登录时间',
    PRIMARY KEY (user_id),
    UNIQUE KEY uk_username (username),
    KEY idx_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin COMMENT = '平台用户';

CREATE TABLE sys_role_permission (
    role             VARCHAR(16) NOT NULL COMMENT '角色名（大写；ADMIN 不入库，代码级放行）',
    denied_tools     JSON        NOT NULL COMMENT '禁用工具名清单',
    denied_skills    JSON        NOT NULL COMMENT '禁用技能名清单',
    denied_subagents JSON        NOT NULL COMMENT '禁用子代理名清单',
    update_time      DATETIME    NOT NULL COMMENT '规则最后更新时间',
    PRIMARY KEY (role)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin COMMENT = '角色权限黑名单（JSON 列需 MySQL 5.7.8+）';
