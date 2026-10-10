-- P2 图谱配置：graph-schema / ontology-profile / dedup-rules / disjoint-rules 四个 JSON 的目标表
-- utf8mb4_bin（区分大小写）对齐 Java equals 语义
-- 嵌套结构用 JSON 列（MySQL 8.0 JSON 列不允许 DEFAULT，一律 NOT NULL，插入必须全字段）

-- graph-schema.json 顶层全局 version（备份 zip 文件名 v 段与 schemaView API 数据源）
CREATE TABLE graph_schema_meta (
    id          TINYINT   NOT NULL COMMENT '固定 1（单行）',
    version     INT       NOT NULL COMMENT 'schema 全局版本（每次变更 +1）',
    update_time DATETIME  NOT NULL COMMENT '最后变更时间',
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin COMMENT = '图谱 schema 元数据（单行）';

-- 类（节点类型）定义：graph-schema.json 的 classes[].类级字段
CREATE TABLE graph_class (
    name        VARCHAR(64)  NOT NULL COMMENT '类名（唯一标识）',
    cn_name     VARCHAR(128) NOT NULL COMMENT '中文名',
    layer       VARCHAR(16)  NOT NULL COMMENT 'concept | instance',
    key_prop    VARCHAR(64)  NOT NULL COMMENT '唯一键属性名',
    description TEXT         NULL     COMMENT '语义说明',
    ord         INT          NOT NULL COMMENT '声明顺序（保持文件内顺序）',
    PRIMARY KEY (name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin COMMENT = '图谱类定义';

-- 类属性定义：classes[].properties[]（复合主键 class_name + prop_name）
CREATE TABLE graph_class_prop (
    class_name  VARCHAR(64)  NOT NULL COMMENT '所属类名',
    prop_name   VARCHAR(64)  NOT NULL COMMENT '属性名',
    cn_name     VARCHAR(128) NULL     COMMENT '中文名',
    data_type   VARCHAR(16)  NOT NULL COMMENT 'string | number',
    required    TINYINT      NOT NULL COMMENT '是否必填（1/0）',
    description TEXT         NULL     COMMENT '语义说明',
    ord         INT          NOT NULL COMMENT '声明顺序',
    PRIMARY KEY (class_name, prop_name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin COMMENT = '图谱类属性定义';

-- 关系定义：classes[].relations[]（声明于发起方一侧；props 为关系自身属性定义列表，JSON 列）
CREATE TABLE graph_rel_def (
    from_class  VARCHAR(64)  NOT NULL COMMENT '发起类名',
    rel_name    VARCHAR(128) NOT NULL COMMENT '关系类型名（全 schema 内唯一）',
    cn_name     VARCHAR(128) NULL     COMMENT '中文名',
    to_class    VARCHAR(64)  NOT NULL COMMENT '目标类名',
    description TEXT         NULL     COMMENT '语义说明',
    example     TEXT         NULL     COMMENT '示例（from -> to）',
    props       JSON         NOT NULL COMMENT '关系属性定义列表（PropertyDef[]，可为空数组）',
    ord         INT          NOT NULL COMMENT '声明顺序',
    PRIMARY KEY (from_class, rel_name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin COMMENT = '图谱关系定义';

-- ontology-profile.json：构建期问询记录（单行；items 保留文件原序，updatedAt 为 ISO 字符串原样保存）
CREATE TABLE ontology_profile (
    id          VARCHAR(16) NOT NULL COMMENT "固定 'default'（单行）",
    version     INT         NOT NULL COMMENT '版本号（每次写入 +1）',
    items       JSON        NOT NULL COMMENT '问询条目数组（ProfileEntry[]，key 唯一）',
    update_time DATETIME    NOT NULL COMMENT '最后写入时间',
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin COMMENT = '构建期问询记录（单行）';

-- dedup-rules.json：去重规则集（单套规则一行；updated_at 为 epoch 毫秒，API 契约即 long）
CREATE TABLE rule_dedup (
    id                   VARCHAR(64)  NOT NULL COMMENT '规则集唯一 ID',
    name                 VARCHAR(128) NOT NULL COMMENT '规则集展示名',
    version              INT          NOT NULL COMMENT '版本号（每次写入 +1，乐观锁）',
    updated_at           BIGINT       NOT NULL COMMENT '最后更新时间（epoch 毫秒）',
    suffix_table         JSON         NOT NULL COMMENT 'Normalize 后缀表 Map<label, string[]>',
    label_config         JSON         NOT NULL COMMENT 'label 配置 Map<label, DedupLabelConfig>',
    global_conflict_props JSON        NOT NULL COMMENT '全局冲突检测关键属性（可为空数组）',
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin COMMENT = '去重规则集';

-- disjoint-rules.json：互斥约束词典（单行；rules 保留文件原序 R001..Rnnn，version 计数与文件一致）
CREATE TABLE rule_disjoint (
    id          VARCHAR(16) NOT NULL COMMENT "固定 'default'（单行）",
    version     INT         NOT NULL COMMENT '版本号（每次写入 +1）',
    rules       JSON        NOT NULL COMMENT '规则数组（DisjointRule[]：id + text）',
    update_time DATETIME    NOT NULL COMMENT '最后写入时间',
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin COMMENT = '互斥约束词典（单行）';
