package com.conca.ontology.shared.db;

import javax.sql.DataSource;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.extensions.mysql.store.JdbcStore;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;

/**
 * 各链 HarnessAgent 的分布式存储工厂（工作区共享存储模式，四链统一架构）：
 * DistributedStore 自定义组合 = 本链状态表（agent_state_*，P8 既有）+ 本链 KV 表（agent_kv_*）。
 *
 * <p>不用官方 MysqlDistributedStore.create(dataSource) 的原因（源码核实）：
 * <ul>
 *   <li>其 agentStateStore() 用默认两参构造，指向 agentscope 库 agentscope_sessions 表，
 *       与既有会话状态表（agent_state_main 等）不同库不同表，直接使用会导致存量会话全部不可见；</li>
 *   <li>该类已标记 @Deprecated(forRemoval)，官方建议迁移至 agentscope-extensions-jdbc 模块（本项目未引入）。</li>
 * </ul>
 *
 * <p>一链一对表（与 P8"一个 agent 一张 state 表"思路同构）：
 * <ul>
 *   <li>主链 ontology-assistant：agent_state_main + agent_kv_main；</li>
 *   <li>改写链 rewrite-*（四类型共享）：agent_state_rewrite + agent_kv_rewrite；</li>
 *   <li>评测判分链 eval-judge：agent_state_judge + agent_kv_judge；</li>
 *   <li>取证评测链 eval-agentic-judge：agent_state_agentic_judge + agent_kv_agentic_judge。</li>
 * </ul>
 * 状态表沿用既有装配（本机/共享存储两模式同表，切换零迁移、会话连续）；KV 表由 JdbcStore
 * initializeSchema=true 幂等建表（CREATE TABLE IF NOT EXISTS，Flyway 不管理）。列：namespace_path
 * （0x1F 连接）+ item_key 主键、value_json、version（乐观锁）、updated_at。BackupService 走
 * information_schema 自动发现，agent_kv_* 家族自动进备份。
 *
 * <p>store 实例缓存于返回对象内：JdbcStore 构造期执行建表 DDL，避免框架多次取用重复执行；
 * 多 agent 共享一链时（如 rewrite 四类型），调用方应缓存工厂返回值，勿每次重建。
 *
 * @author tianbaoxing
 * @date 2026-10-19
 */
public final class AgentDistributedStores {

    /** 主链工作区 KV 表名 */
    public static final String KV_MAIN = "agent_kv_main";
    /** 改写链工作区 KV 表名（rewrite-* 四类型共享） */
    public static final String KV_REWRITE = "agent_kv_rewrite";
    /** 评测判分链工作区 KV 表名 */
    public static final String KV_JUDGE = "agent_kv_judge";
    /** 取证评测链工作区 KV 表名 */
    public static final String KV_AGENTIC_JUDGE = "agent_kv_agentic_judge";

    private AgentDistributedStores() {
    }

    /**
     * 按链组合分布式存储：状态表（传 {@link MySqlAgentStateStores} 常量）+ KV 表（传本类 KV_* 常量）。
     */
    public static DistributedStore create(DataSource dataSource, String stateTable, String kvTable) {
        AgentStateStore stateStore = MySqlAgentStateStores.create(dataSource, stateTable);
        BaseStore baseStore = JdbcStore.builder(dataSource)
                .tableName(kvTable)
                .initializeSchema(true)
                .build();
        return new DistributedStore() {
            @Override
            public AgentStateStore agentStateStore() {
                return stateStore;
            }

            @Override
            public BaseStore baseStore() {
                return baseStore;
            }
        };
    }
}
