package com.conca.ontology.eval.graders.llm.answer;

import com.conca.ontology.eval.graders.llm.LlmJudgeGrader;

import org.springframework.stereotype.Component;

import com.conca.ontology.eval.JudgeClient;

/**
 * 推理有据性评测（LLM-as-judge，对齐 OpenJudge ReasoningGroundednessGrader）：
 * 评估回答结论是否以图谱查询证据（基类注入的 context）为据而非臆测。
 * 无工具调用时跳过（无观察证据可依，不消耗 judge 调用）。
 *
 * @author tianbaoxing
 * @date 2026-09-16
 */
@Component
public class LlmReasoningGroundednessGrader extends LlmJudgeGrader {

    public LlmReasoningGroundednessGrader(JudgeClient client) {
        super(client);
    }

    @Override
    public String name() {
        return "llm_reasoning_groundedness";
    }

    @Override
    public String description() {
        return "推理有据性（LLM 判分，消耗 tokens）：评估回答结论是否以图谱查询证据为据而非臆测；无工具调用时跳过";
    }

    @Override
    protected String criteria() {
        return "评估回答中的结论是否被证据上下文支撑：5=结论均被证据支撑；4=基本有据；3=部分有据；"
                + "2=多数无据；1=凭空臆测。";
    }

    @Override
    protected boolean requireToolCalls() {
        return true;
    }
}
