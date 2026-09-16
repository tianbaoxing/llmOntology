package com.conca.ontology.eval.graders.llm.tool;

import com.conca.ontology.eval.graders.llm.LlmJudgeGrader;

import org.springframework.stereotype.Component;

import com.conca.ontology.eval.GraderInput;
import com.conca.ontology.eval.JudgeClient;

/**
 * 工具选择合理性评测（LLM-as-judge，对齐 OpenJudge ToolSelectionGrader）：
 * 与工具调用正确性互补，侧重「该不该用这些工具」（有无缺失或多余）。
 * 无工具调用时跳过（不消耗 judge 调用）。
 */
@Component
public class LlmToolSelectionGrader extends LlmJudgeGrader {

    public LlmToolSelectionGrader(JudgeClient client) {
        super(client);
    }

    @Override
    public String name() {
        return "llm_tool_selection";
    }

    @Override
    public String description() {
        return "工具选择合理性（LLM 判分，消耗 tokens）：评估该不该用这些工具、有无缺失或多余；无工具调用时跳过";
    }

    @Override
    protected String criteria() {
        return "评估工具选择合理性：5=选择恰当无冗余；4=恰当但略有冗余；3=可用但非最优；"
                + "2=明显多余调用；1=完全误选。";
    }

    @Override
    protected String extraBlock(GraderInput input) {
        return renderTrajectory(input.record());
    }

    @Override
    protected boolean requireToolCalls() {
        return true;
    }
}
