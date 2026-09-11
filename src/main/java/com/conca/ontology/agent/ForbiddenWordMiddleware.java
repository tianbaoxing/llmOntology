package com.conca.ontology.agent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.conca.ontology.service.ForbiddenWordService;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.middleware.HarnessRuntimeMiddleware;

import reactor.core.publisher.Flux;

/**
 * 禁用词拦截中间件：onAgent 层短路，取代 AgentChatService 的前置检查。
 *
 * 命中禁用词时不调用 next（不进入 ReAct 生命周期，不调模型、不耗 token），
 * 但把「用户消息 + 拒绝提示」按 get → mutate → save 官方模式写入该会话
 * AgentState（agent_state.json），刷新后历史接口可读回该轮交换；
 * 同时返回与正常回答同构的合成事件流（round start → answer → round end → done），
 * 前端 live 渲染与刷新后历史渲染保持一致（普通 assistant 气泡）。
 *
 * order()=0：位于全部 harness 内置中间件（默认 order=1）内层，短路后
 * TranscriptMiddleware 等收尾逻辑照常执行（log.jsonl 转录补全该轮交换）。
 * 实现 HarnessRuntimeMiddleware 标记接口：harness 派生子 agent 时不复制本中间件
 * （子 agent 任务文本不经此检查，也避免污染父会话 state）。
 */
public class ForbiddenWordMiddleware implements HarnessRuntimeMiddleware {

    private static final Logger log = LoggerFactory.getLogger(ForbiddenWordMiddleware.class);

    private final ForbiddenWordService forbiddenWordService;

    public ForbiddenWordMiddleware(ForbiddenWordService forbiddenWordService) {
        this.forbiddenWordService = forbiddenWordService;
    }

    public int order() {
        // 0 = 最内层：短路只跳过核心 ReAct 循环，harness 内置中间件（order=1）包裹在外照常收尾
        return 0;
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        List<String> hits = checkUserMessages(input);
        if (hits.isEmpty() || !(agent instanceof ReActAgent react)) {
            return next.apply(input); // 未命中（或防御性类型不匹配）→ 原样放行
        }
        String tip = "⚠ 您的消息包含禁用词：" + String.join("、", hits) + "，请修改后重新发送";
        RuntimeContext rc = ctx != null ? ctx : RuntimeContext.empty();
        Msg tipMsg = Msg.builder().role(MsgRole.ASSISTANT).textContent(tip).build();

        try {
            AgentState state = react.getAgentState(rc.getUserId(), rc.getSessionId());
            for (Msg msg : input.msgs()) {
                if (msg.getRole() == MsgRole.USER) {
                    state.contextMutable().add(msg); // 原样入 state（Msg 自带 id/时间戳）
                }
            }
            state.contextMutable().add(tipMsg);
            // 正常流程由 beforeAgentExecution 回写 ctx；短路时自行补上，
            // 供外层 TranscriptMiddleware 等经 rc.getAgentState() 解析到本会话 state
            rc.setAgentState(state);
            react.saveAgentState(rc.getUserId(), rc.getSessionId());
            log.info("禁用词拦截已落盘（session={}，hits={}）", rc.getSessionId(), hits);
        } catch (Exception e) {
            // 落盘失败降级：仅本次不持久化，提示流程不阻断
            log.warn("禁用词拦截落盘失败（session={}）：{}", rc.getSessionId(), e.getMessage());
        }

        // 合成事件流：AgentChatService.handleEvent 现有映射原样转发为 round/answer/done
        String replyId = UUID.randomUUID().toString().replace("-", "");
        return Flux.just(
                new AgentStartEvent(null, replyId, react.getName()),
                new ModelCallStartEvent(replyId),
                new TextBlockDeltaEvent(replyId, "forbidden_tip", tip),
                new ModelCallEndEvent(replyId, null),
                new AgentResultEvent(tipMsg),
                new AgentEndEvent(replyId));
    }

    /** 汇总输入中全部 USER 消息命中的禁用词（去重保序） */
    private List<String> checkUserMessages(AgentInput input) {
        LinkedHashSet<String> hits = new LinkedHashSet<>();
        for (Msg msg : input.msgs()) {
            if (msg.getRole() == MsgRole.USER) {
                hits.addAll(forbiddenWordService.check(textOf(msg)));
            }
        }
        return new ArrayList<>(hits);
    }

    private static String textOf(Msg msg) {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock b : msg.getContent()) {
            if (b instanceof TextBlock tb) {
                sb.append(tb.getText());
            }
        }
        return sb.toString();
    }
}