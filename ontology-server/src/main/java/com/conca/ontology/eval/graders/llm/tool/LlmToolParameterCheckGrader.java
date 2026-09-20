package com.conca.ontology.eval.graders.llm.tool;

import com.conca.ontology.eval.graders.llm.LlmJudgeGrader;

import org.springframework.stereotype.Component;

import com.conca.ontology.eval.model.GraderInput;
import com.conca.ontology.eval.JudgeClient;

/**
 * 工具参数抽取评测（LLM-as-judge，对齐 OpenJudge ToolParameterCheckGrader）：
 * 检查轨迹中工具入参（如 Cypher 中的实体名/属性值）是否来自问题本身，是否编造
 * 问题中不存在的实体或条件（防幻觉参数）。无工具调用时跳过（不消耗 judge 调用）。
 */
@Component
public class LlmToolParameterCheckGrader extends LlmJudgeGrader {

    public LlmToolParameterCheckGrader(JudgeClient client) {
        super(client);
    }

    @Override
    public String name() {
        return "llm_tool_parameter_check";
    }

    @Override
    public String description() {
        return "工具参数抽取（LLM 判分，消耗 tokens）：检查工具入参是否来自问题本身、有无编造实体或条件；无工具调用时跳过";
    }

    @Override
    protected String criteria() {
        return "逐条检查工具调用的参数是否忠实抽取自问题：5=全部参数均能在问题中找到出处，无编造；"
                + "4=参数基本正确，个别拼写或大小写出入；3=偶有一处编造（问题中不存在的实体/条件）；"
                + "2=多处参数编造；1=参数与问题基本无关。";
    }

    @Override
    protected String extraBlock(GraderInput input) {
        String exp = renderExpectedBlock(input, false);
        String traj = renderTrajectory(input.record());
        return (exp.isEmpty() ? "" : exp + "\n\n") + traj;
    }

    @Override
    protected boolean requireToolCalls() {
        return true;
    }
}
