-- P6（三）：data/eval/datasets/*.jsonl（每数据集一文件，每非空行一样本）→ dataset + dataset_sample 两表
-- dataset 每数据集一行：name = 原 .jsonl 文件名（业务键，eval_run.dataset 同名引用）；
--   不加 created_at（源文件无时间戳、UI 不显示）；样本数走 COUNT(*) 派生不落列
-- dataset_sample 每非空行样本一行：自增 id 兼作文件行序（插入序即原文件行序，中删后自然前移
--   = 原文件删行语义），seq 定位 = 按 id 升序行号（对齐 eval_run_sample 先例）
-- sample_id NULLable：原行无 id 键，读取路径按 line-{seq} 兜底（保真 loadSamples 现行为）；
--   不加 UNIQUE(dataset_name, sample_id)：手改文件理论可含重复 id，导入不被约束炸掉，id 查重走写路径预检（409 文案不变）
-- query/response NULLable：多轮样本无 query/response 键（turns 承载），读取按空串/省略键兜底
-- reference_response JSON Object 保真（String / 数字 / 数组原样，对齐 eval_run_sample 先例）
-- turns/skills/tools/mcps 类型化 JSON 保序：NULL = 原行无此键（回读省略键），
--   区别于 eval_run_sample 的 NOT NULL 归一 []——数据集行以「文件行保真」为先（parseStringList 空→null 同构）
-- rubric：loadSamples 读路径支持但源文件暂未使用，NULLable 保真保留
-- 查询路径：listDatasets 按 name 升序 + 每数据集 COUNT 派生；样本一律按 id 升序 = 原文件行序

CREATE TABLE dataset (
    name              VARCHAR(128) NOT NULL COMMENT '数据集名（= 原 .jsonl 文件名，业务键）',
    PRIMARY KEY (name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin
  COMMENT = '评测数据集（每数据集一行；源 data/eval/datasets/*.jsonl）';

CREATE TABLE dataset_sample (
    id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键，兼作文件行序（插入序即原文件非空行序）',
    dataset_name      VARCHAR(128) NOT NULL COMMENT '所属数据集名（dataset.name）',
    sample_id         VARCHAR(64)      NULL COMMENT '样本 id（如 q001；原行无 id 键为 NULL，读取按 line-{seq} 兜底）',
    query             MEDIUMTEXT      NULL COMMENT '单轮提问（多轮样本无此键为 NULL，读取按空串兜底）',
    response          MEDIUMTEXT      NULL COMMENT '预置回答（评测时跳过 agent 直接判分；缺省 NULL 回读省略键）',
    reference_response JSON         NULL COMMENT '参考答案 Object（String/数字/数组原样保真）',
    tolerance         DOUBLE          NULL COMMENT '数值容差（number_accuracy 用）',
    rubric            TEXT            NULL COMMENT '评分 rubric（读路径支持，源文件暂未使用）',
    turns             JSON            NULL COMMENT '多轮脚本 [{query,response?}]（保序，至少 2 轮；单轮样本 NULL）',
    skills            JSON            NULL COMMENT '期望 skills 名称列表（NULL = 原行无此键）',
    tools             JSON            NULL COMMENT '期望普通工具名称列表（NULL = 原行无此键）',
    mcps              JSON            NULL COMMENT '期望 MCP 工具列表（NULL = 原行无此键）',
    PRIMARY KEY (id),
    KEY idx_ds_id (dataset_name, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin
  COMMENT = '评测数据集样本明细（每非空行样本一行，id 升序 = 原文件行序）';
