package com.conca.ontology.agent;

import java.util.UUID;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.conca.ontology.model.content.ContentSecurityCheckResult;
import com.conca.ontology.service.ContentSecurityService;

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
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.middleware.HarnessRuntimeMiddleware;
import reactor.core.publisher.Flux;

public class ContentSecurityMiddleware implements HarnessRuntimeMiddleware {

    private static final Logger log = LoggerFactory.getLogger(ContentSecurityMiddleware.class);

    private final ContentSecurityService securityService;

    public ContentSecurityMiddleware(ContentSecurityService securityService) {
        this.securityService = securityService;
    }

    public int order() {
        return 0;
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {

        ContentSecurityCheckResult result = securityService.checkUserMessages(input.msgs());

        if (!result.shouldBlock() || !(agent instanceof ReActAgent react)) {
            return next.apply(input);
        }

        String tip = result.blockTip();
        if (tip == null || tip.isBlank()) {
            tip = "⚠ 您的消息存在安全风险，已被拦截，请修改后重新发送";
        }

        RuntimeContext rc = ctx != null ? ctx : RuntimeContext.empty();
        Msg tipMsg = Msg.builder().role(MsgRole.ASSISTANT).textContent(tip).build();

        try {
            AgentState state = react.getAgentState(rc.getUserId(), rc.getSessionId());
            for (Msg msg : input.msgs()) {
                if (msg.getRole() == MsgRole.USER) {
                    state.contextMutable().add(msg);
                }
            }
            state.contextMutable().add(tipMsg);
            rc.setAgentState(state);
            react.saveAgentState(rc.getUserId(), rc.getSessionId());
            log.info("内容安全拦截已落盘（session={}，injectionDecision={}）",
                    rc.getSessionId(),
                    result.injectionResult().decision());
        } catch (Exception e) {
            log.warn("内容安全拦截落盘失败（session={}）：{}", rc.getSessionId(), e.getMessage());
        }

        String replyId = UUID.randomUUID().toString().replace("-", "");
        return Flux.just(
                new AgentStartEvent(null, replyId, react.getName()),
                new ModelCallStartEvent(replyId),
                new TextBlockDeltaEvent(replyId, "content_security_block", tip),
                new ModelCallEndEvent(replyId, null),
                new AgentResultEvent(tipMsg),
                new AgentEndEvent(replyId));
    }
}
