package com.conca.ontology.eval.graders.llm.tool;

import com.conca.ontology.eval.graders.llm.LlmJudgeGrader;

import org.springframework.stereotype.Component;

import com.conca.ontology.eval.model.GraderInput;
import com.conca.ontology.eval.JudgeClient;

/**
 * 工具调用正确性评测（LLM-as-judge，对齐 OpenJudge ToolCallAccuracyGrader）：
 * 评估 agent 轨迹中工具选择与参数是否正确（如该用 graph_query 却用了别的）。
 * 无工具调用时跳过（不消耗 judge 调用）。
 */
@Component
public class LlmToolCallAccuracyGrader extends LlmJudgeGrader {

    public LlmToolCallAccuracyGrader(JudgeClient client) {
        super(client);
    }

    @Override
    public String name() {
        return "llm_tool_call_accuracy";
    }

    @Override
    public String description() {
        return "工具调用正确性（LLM 判分，消耗 tokens）：评估轨迹中工具选择与参数是否正确；无工具调用时跳过";
    }

    @Override
    protected String criteria() {
        return "结合问题评估工具调用的正确性：5=工具选择与参数全部正确高效；4=正确但参数欠精确；"
                + "3=基本可用；2=多次不当调用；1=工具或参数明显错误。";
    }

    @Override
    protected String extraBlock(GraderInput input) {
        String exp = renderExpectedBlock(input);
        String traj = renderTrajectory(input.record());
        return (exp.isEmpty() ? "" : exp + "\n\n") + traj;
    }

    @Override
    protected boolean requireToolCalls() {
        return true;
    }
}
