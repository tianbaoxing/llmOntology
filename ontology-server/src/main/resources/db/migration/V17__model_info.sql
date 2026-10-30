-- AI 模型信息：docs/all_ai_models.csv（Epoch AI 数据集，57 列 / 3,677 行）→ 每模型一行，独立域无外键关联
-- 列名映射规则：原始表头小写，空格与 ( ) ? - 等非字母数字字符统一替换为 _（连续合并、去首尾）；
--   每列 COMMENT 尾注原 CSV 列名，保证与源文件可对照追溯
-- model 为自然主键（源数据已验证无重复；utf8mb4_bin 精确匹配），不设自增 id
-- 7 个 FLOP/规模类列统一 DECIMAL(45,10)：覆盖源文件科学计数法（实测上限 1.13e+27）并保留小数
-- 布尔 4 列源值 True/Yes/No → TINYINT(1)（1=是 0=否，空=NULL），由导入脚本转换
-- last_modified 源值带 +00:00 时区偏移，入库截断为 UTC 语义 DATETIME
-- 多值列（domain/task/approach 等）保持源文件逗号分隔原文，不拆子表
-- 数据导入见 db/import/model_info_import.sql，手工执行（非 Flyway 托管：LOAD LOCAL INFILE 依赖客户端
--   参数与本机文件路径，且数据集持续更新，与迁移只执行一次的语义冲突）

CREATE TABLE model_info (
    model                              VARCHAR(160)   NOT NULL COMMENT '模型名称，自然主键（原列 Model）',
    domain                             VARCHAR(128)   NULL COMMENT '领域，逗号分隔多值（原列 Domain）',
    task                               VARCHAR(512)   NULL COMMENT '任务，逗号分隔多值（原列 Task）',
    organization                       VARCHAR(1024)  NULL COMMENT '发布组织，多值（原列 Organization）',
    authors                            TEXT           NULL COMMENT '作者列表（原列 Authors）',
    publication_date                   DATE           NULL COMMENT '发布日期（原列 Publication date）',
    reference                          VARCHAR(512)   NULL COMMENT '参考文献/来源标题（原列 Reference）',
    link                               VARCHAR(1024)  NULL COMMENT '来源链接（原列 Link）',
    citations                          DECIMAL(12,1)  NULL COMMENT '引用数（原列 Citations）',
    notability_criteria                VARCHAR(128)   NULL COMMENT '知名度判定标准（原列 Notability criteria）',
    notability_criteria_notes          TEXT           NULL COMMENT '知名度判定标准备注（原列 Notability criteria notes）',
    parameters                         DECIMAL(45,10) NULL COMMENT '参数量（原列 Parameters）',
    parameters_notes                   TEXT           NULL COMMENT '参数量备注（原列 Parameters notes）',
    training_compute_flop              DECIMAL(45,10) NULL COMMENT '训练算力，FLOP（原列 Training compute (FLOP)）',
    training_compute_notes             TEXT           NULL COMMENT '训练算力备注（原列 Training compute notes）',
    training_dataset_size_total        DECIMAL(45,10) NULL COMMENT '训练数据集总规模，token 数（原列 Training dataset size (total)）',
    dataset_size_notes                 TEXT           NULL COMMENT '数据集规模备注（原列 Dataset size notes）',
    training_time_hours                DECIMAL(12,2)  NULL COMMENT '训练时长，小时（原列 Training time (hours)）',
    training_time_notes                TEXT           NULL COMMENT '训练时长备注（原列 Training time notes）',
    training_hardware                  VARCHAR(128)   NULL COMMENT '训练硬件（原列 Training hardware）',
    approach                           VARCHAR(128)   NULL COMMENT '训练方法，逗号分隔多值（原列 Approach）',
    confidence                         VARCHAR(16)    NULL COMMENT '数据置信度：Confident/Likely/Speculative/Unknown（原列 Confidence）',
    abstract                           TEXT           NULL COMMENT '摘要，可含多行文本（原列 Abstract）',
    epochs                             DECIMAL(10,4)  NULL COMMENT '训练轮数（原列 Epochs）',
    wikitext_and_penn_treebank_data    VARCHAR(255)   NULL COMMENT 'WikiText 与 Penn Treebank 数据标注（原列 WikiText and Penn Treebank data）',
    model_accessibility                VARCHAR(64)    NULL COMMENT '模型可获取方式（原列 Model accessibility）',
    country_of_organization            VARCHAR(1024)  NULL COMMENT '组织所在国家，多值（原列 Country (of organization)）',
    base_model                         VARCHAR(128)   NULL COMMENT '基座模型（原列 Base model）',
    finetune_compute_flop              DECIMAL(45,10) NULL COMMENT '微调算力，FLOP（原列 Finetune compute (FLOP)）',
    finetune_compute_notes             TEXT           NULL COMMENT '微调算力备注（原列 Finetune compute notes）',
    hardware_quantity                  DECIMAL(15,2)  NULL COMMENT '训练硬件数量（原列 Hardware quantity）',
    hardware_utilization_mfu           DECIMAL(6,4)   NULL COMMENT '硬件利用率，MFU（原列 Hardware utilization (MFU)）',
    last_modified                      DATETIME       NULL COMMENT '数据最后修改时间，UTC（原列 Last modified，源值带 +00:00 偏移，入库截断）',
    training_cloud_compute_vendor      VARCHAR(64)    NULL COMMENT '训练云计算厂商（原列 Training cloud compute vendor）',
    training_data_center               VARCHAR(255)   NULL COMMENT '训练数据中心（原列 Training data center）',
    archived_links                     VARCHAR(512)  NULL COMMENT '存档链接（原列 Archived links）',
    batch_size                         DECIMAL(20,2)  NULL COMMENT '训练批大小（原列 Batch size）',
    batch_size_notes                   TEXT           NULL COMMENT '批大小备注（原列 Batch size notes）',
    organization_categorization        VARCHAR(512)   NULL COMMENT '组织分类，逗号分隔多值（原列 Organization categorization）',
    foundation_model                   TINYINT(1)     NULL COMMENT '是否基础模型：1=是 0=否（原列 Foundation model，源值 True）',
    training_compute_lower_bound       DECIMAL(45,10) NULL COMMENT '训练算力下界，FLOP（原列 Training compute lower bound）',
    training_compute_upper_bound       DECIMAL(45,10) NULL COMMENT '训练算力上界，FLOP（原列 Training compute upper bound）',
    training_chip_hours                DECIMAL(18,2)  NULL COMMENT '训练芯片小时数（原列 Training chip-hours）',
    training_code_accessibility        VARCHAR(32)    NULL COMMENT '训练代码可获取性（原列 Training code accessibility）',
    accessibility_notes                TEXT           NULL COMMENT '可获取性备注（原列 Accessibility notes）',
    possibly_over_1e23_flop            TINYINT(1)     NULL COMMENT '算力是否可能超 1e23 FLOP：1=是 0=否（原列 Possibly over 1e23 FLOP，源值 True）',
    training_compute_cost_2023_usd     DECIMAL(20,8)  NULL COMMENT '训练算力成本，2023 年美元（原列 Training compute cost (2023 USD)）',
    utilization_notes                  TEXT           NULL COMMENT '利用率备注（原列 Utilization notes）',
    numerical_format                   VARCHAR(64)    NULL COMMENT '数值精度格式，如 BF16/FP8（原列 Numerical format）',
    frontier_model                     TINYINT(1)     NULL COMMENT '是否前沿模型：1=是 0=否（原列 Frontier model，源值 True）',
    training_power_draw_w              DECIMAL(20,8)  NULL COMMENT '训练功耗，瓦（原列 Training power draw (W)）',
    training_compute_estimation_method VARCHAR(128)   NULL COMMENT '训练算力估算方法（原列 Training compute estimation method）',
    hugging_face_developer_id          VARCHAR(64)    NULL COMMENT 'Hugging Face 开发者 ID（原列 Hugging Face developer id）',
    post_training_compute_flop         DECIMAL(45,10) NULL COMMENT '后训练算力，FLOP（原列 Post-training compute (FLOP)）',
    post_training_compute_notes        TEXT           NULL COMMENT '后训练算力备注（原列 Post-training compute notes）',
    hardware_utilization_hfu           DECIMAL(6,4)   NULL COMMENT '硬件利用率，HFU（原列 Hardware utilization (HFU)）',
    open_model_weights                 TINYINT(1)     NULL COMMENT '是否开放模型权重：1=是 0=否（原列 Open model weights?，源值 Yes/No）',
    PRIMARY KEY (model),
    KEY idx_publication_date (publication_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin
  COMMENT = 'AI 模型信息（源 Epoch AI all_ai_models.csv，每模型一行；导入脚本 db/import/model_info_import.sql）';
