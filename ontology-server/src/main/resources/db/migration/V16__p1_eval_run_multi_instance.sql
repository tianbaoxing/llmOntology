-- P1（多实例部署）：评测任务全局互斥 + 跨实例进度可见
--
-- 全局单 running 约束：running_flag 虚拟生成列 + uk_running 唯一索引。
--   MySQL 唯一索引忽略 NULL → 全表最多一行 status='running'；
--   start 抢锁 = INSERT running 行（撞 uk_running 即 409，原子无竞态窗口），
--   替代原"内存 current 检查 + DB zombie 检查"（后者检查与插入非原子，两实例可各跑一个）。
--   锁生命周期与任务行同构，无忘记释放路径：
--     完成/失败 UPDATE status 释放、deleteRun 删行释放、
--     实例崩溃行保持 running = 持锁阻塞后续 start（安全方向失败，靠心跳判活后人工清理）。
--
-- owner_instance：发起评测的实例标识（IP:端口@启动时间，如 192.168.1.100:9876@20261026-143025，
--   随任务行落库；409 文案定位与排查用）。
-- heartbeat_at：进度心跳，writeTaskMeta 落库时刷新（每样本必写；多轮样本每轮、
--   judge 每评测器补刷）。读取侧超阈值（15 分钟）无心跳判实例失联：
--   progress/listRuns 显示 interrupted、deleteRun 放行清理。
--
-- 存量 running 行：Flyway 执行时点早于服务就绪，不可能有存活任务，统一置 interrupted
--   （兼防存量多行 running 导致 uk_running 创建失败）。

UPDATE eval_run SET status = 'interrupted' WHERE status = 'running';

ALTER TABLE eval_run
    ADD COLUMN owner_instance VARCHAR(96) NULL
        COMMENT '发起评测的实例标识（IP:端口@启动时间，IPv6 最坏 67 字符故取 96；单实例部署同样落库无害）'
        AFTER user_id,
    ADD COLUMN heartbeat_at DATETIME NULL
        COMMENT '进度心跳时间（writeTaskMeta 落库时刷新；超 15 分钟无心跳判实例失联）'
        AFTER owner_instance,
    ADD COLUMN running_flag TINYINT GENERATED ALWAYS AS (IF(status = 'running', 1, NULL)) VIRTUAL
        COMMENT '虚拟生成列：status=running 时为 1 否则 NULL（配合 uk_running 实现全局单 running；实体不映射，禁止直接写入）',
    ADD UNIQUE KEY uk_running (running_flag);
