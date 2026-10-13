-- P6（下）：data/eval/results/eval-*.json（每任务一文件）→ eval_run + eval_run_sample 两表
-- run 一行：运行态/完成态同表共存，完成态专有列（finished_at/summary 各列）在运行态为 NULL；
--   status NULL = 老完成文件无 status 字段，读取时按 success 处理（对齐原 readResult 逻辑）
-- summary 逐列拆出全 NULLable：老完成文件无 judge/human* → NULL，回读重建时 NULL 的 key 省略，
--   输出 key 集与原文件逐代一致（老 8 键 / 新 12 键）
-- samples 每样本一行：自增 id 兼作 samples 数组顺序（对齐 file_metadata 先例），
--   UNIQUE(run_id, sample_id) 防重复导入
-- 老格式 samples[].expected 为死字段（全代码零消费、前端零引用）：迁移时直接丢弃不落库
-- started_at/finished_at 存 ISO-8601 原文保纳秒（对齐 skill_analysis / agent_definition 先例）
-- graders / tool_calls / dialogue / scores / reference_response / judge 为 JSON 列（JacksonTypeHandler 保序）
-- query/answer MEDIUMTEXT 容长文本；error/agent_error 存异常消息原文、长度无上限故 TEXT
-- 查询路径：按 run_id 主键点查；listRuns 全表 run_id 倒序（= 原文件名字典倒序时间序）；
--   样本按 id 升序 = samples 数组原序

CREATE TABLE eval_run (
    run_id            VARCHAR(32)  NOT NULL COMMENT '任务 id（eval-yyyyMMdd-HHmmss-随机码，原文件名去 .json）',
    dataset           VARCHAR(128) NOT NULL COMMENT '数据集文件名',
    dataset_hash      VARCHAR(64)      NULL COMMENT '数据集内容 SHA-256（老 run 无）',
    graders           JSON         NOT NULL COMMENT '评测器名有序数组（判分与对比均依赖顺序）',
    judge_runs        INT              NULL COMMENT 'LLM 判分次数（>1 多次取平均降噪；老 run 无）',
    status            VARCHAR(16)      NULL COMMENT 'running|success|failed|awaiting_human（老完成文件无 → NULL，读取按 success）',
    detail            VARCHAR(255)     NULL COMMENT '进度描述（running 态；评测 N/M：样本问题摘要 ≤30 字）',
    percent           INT              NULL COMMENT '进度百分比 0-100（running 态）',
    error             TEXT             NULL COMMENT '失败原因（failed 态；异常消息原文长度无上限）',
    user_id           VARCHAR(64)      NULL COMMENT '发起人用户 id（start 时留痕；老完成文件被整文件覆写而丢失）',
    started_at        VARCHAR(64)  NOT NULL COMMENT '开始时间 ISO-8601 原文（保纳秒）',
    finished_at       VARCHAR(64)      NULL COMMENT '完成时间 ISO-8601 原文（running 态 NULL）',
    total             INT              NULL COMMENT '样本总数',
    pass_count        INT              NULL COMMENT '通过数（样本均分 ≥ 阈值且无 error）',
    pass_rate         DOUBLE           NULL COMMENT '通过率（保留 3 位小数）',
    avg_score         DOUBLE           NULL COMMENT '平均分（保留 3 位小数）',
    error_count       INT              NULL COMMENT '异常样本数',
    avg_duration_ms   BIGINT           NULL COMMENT '平均单样本耗时（毫秒）',
    prompt_tokens     BIGINT           NULL COMMENT 'agent 输入 token 合计',
    completion_tokens BIGINT           NULL COMMENT 'agent 输出 token 合计',
    judge             JSON             NULL COMMENT 'LLM 判分汇总 {model,calls,promptTokens,completionTokens}（老 run 无）',
    human_pending     INT              NULL COMMENT '待人工评分数（老 run 无）',
    human_scored      INT              NULL COMMENT '已人工评分数（老 run 无）',
    human_skipped     INT              NULL COMMENT '人工跳过数（老 run 无）',
    PRIMARY KEY (run_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin
  COMMENT = '评测任务（每任务一行，运行态/完成态同表；源 data/eval/results/eval-*.json）';

CREATE TABLE eval_run_sample (
    id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键，兼作 samples 数组顺序（插入序即原文件序）',
    run_id            VARCHAR(32)  NOT NULL COMMENT '所属任务 id（eval_run.run_id）',
    sample_id         VARCHAR(64)  NOT NULL COMMENT '样本 id（数据集内唯一，如 q001）',
    query             MEDIUMTEXT   NOT NULL COMMENT '提问（多轮为「轮1：… ◆ 轮2：…」拼接展示文本）',
    reference_response JSON        NULL COMMENT '参考回答（新格式；老格式 expected 死字段不迁移）',
    tolerance         DOUBLE           NULL COMMENT '数值容差（新格式）',
    turn_count        INT              NULL COMMENT '多轮轮数（单轮样本无此键）',
    response_source   VARCHAR(16)      NULL COMMENT '回答来源：preset（预置回答）| agent',
    answer            MEDIUMTEXT       NULL COMMENT 'agent 最终回答（失败样本为空串）',
    tool_calls        JSON         NOT NULL COMMENT '工具调用轨迹 [{name,args,result,state}]（保序，空为 []）',
    dialogue          JSON             NULL COMMENT '多轮对话记录 [{query,answer}]（仅多轮样本有）',
    rounds            INT          NOT NULL COMMENT '模型调用轮数',
    duration_ms       BIGINT       NOT NULL COMMENT '耗时（毫秒）',
    prompt_tokens     BIGINT       NOT NULL COMMENT '输入 token',
    completion_tokens BIGINT       NOT NULL COMMENT '输出 token',
    agent_error       TEXT             NULL COMMENT 'agent 调用失败原因（成功为 NULL；异常消息原文长度无上限）',
    scores            JSON         NOT NULL COMMENT '各评测器得分 [{name,score,reason,error,metadata,pending}]（保序；人工评分就地改写）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_run_sample (run_id, sample_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin
  COMMENT = '评测任务样本明细（每样本一行，id 升序 = samples 数组原序）';
