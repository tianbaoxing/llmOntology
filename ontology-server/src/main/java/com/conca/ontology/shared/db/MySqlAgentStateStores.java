package com.conca.ontology.shared.db;

import javax.sql.DataSource;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.extensions.mysql.state.MysqlAgentStateStore;

/**
 * 智能体会话状态存储工厂（P8）：官方 MysqlAgentStateStore，每智能体一张 agent_state_{命名空间} 表。
 * 建表 / 乐观锁 / 序列化全部由框架负责（createIfNotExist=true 幂等：库表已存在则仅校验，不重建）。
 * 表名即命名空间，多 store 实例天然隔离，slotId 保持官方原生 {userId}:{sessionId} 拼接。
 * 命名空间固定值见常量；自建智能体直接传 agentId（含连字符合法，官方 SQL 已反引号转义）。
 */
public final class MySqlAgentStateStores {

    /** 与 spring.datasource 同库（官方 store 以 `库`.`表` 全限定名读写） */
    public static final String DATABASE_NAME = "ontology";

    public static final String TABLE_PREFIX = "agent_state_";

    /** 主问答智能体（AgentScopeConfig） */
    public static final String MAIN = "main";
    /** 语义改写智能体（RewriteAgentHolder） */
    public static final String REWRITE = "rewrite";
    /** 评测判分智能体（JudgeAgentHolder） */
    public static final String JUDGE = "judge";
    /** Agentic 取证判分智能体（AgenticJudgeHolder） */
    public static final String AGENTIC_JUDGE = "agentic_judge";

    private MySqlAgentStateStores() {
    }

    /** 按命名空间建 store：表不存在时由框架按官方 DDL 幂等创建 */
    public static AgentStateStore create(DataSource dataSource, String namespace) {
        return new MysqlAgentStateStore(dataSource, DATABASE_NAME, tableNameOf(namespace), true);
    }

    /** 表名（供清理/删除等直连 SQL 拼接；namespace 先经调用方校验） */
    public static String tableNameOf(String namespace) {
        return TABLE_PREFIX + namespace;
    }
}
