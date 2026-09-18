package com.conca.ontology.agent.service;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import io.agentscope.core.state.State;

/**
 * 会话自定义标题：经 AgentStateStore 落盘为
 * {stateDir}/{userId}/{sessionId}/session_title.json（与 agent_state.json 同目录、互不干扰）。
 * 生效优先级：手工命名 > 首条用户消息派生标题（AgentSessionService.titleOf）。
 * 删除会话时框架按会话目录整体清理，无需联动处理。
 */
public record SessionTitleState(String title, String updatedAt) implements State {

    /** 会话标题持久化 key（JsonFileAgentStateStore 对应 session_title.json） */
    public static final String STORE_KEY = "session_title";

    /** 手工命名长度上限（派生标题截断 30，手工命名适当放宽） */
    public static final int TITLE_MAX = 50;

    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 以当前时间戳构造重命名状态 */
    public static SessionTitleState of(String title) {
        return new SessionTitleState(title, TS_FMT.format(LocalDateTime.now()));
    }
}
