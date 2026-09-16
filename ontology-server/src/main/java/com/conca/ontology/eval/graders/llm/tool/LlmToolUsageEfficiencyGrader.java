package com.conca.ontology.eval.graders.llm.tool;

import com.conca.ontology.eval.graders.llm.LlmJudgeGrader;

import org.springframework.stereotype.Component;

import com.conca.ontology.eval.GraderInput;
import com.conca.ontology.eval.JudgeClient;

/**
 * 工具使用效率评测（LLM-as-judge，对齐 OpenJudge ToolUsageEfficiencyGrader）：
 * 检测过度调用与绕圈查询（maxIters=8 场景下尤为相关）——同样的信息是否被
 * 重复查询、是否可以用更少的调用达成目标。无工具调用时跳过（不消耗 judge 调用）。
 */
@Component
public class LlmToolUsageEfficiencyGrader extends LlmJudgeGrader {

    public LlmToolUsageEfficiencyGrader(JudgeClient client) {
        super(client);
    }

    @Override
    public String name() {
        return "llm_tool_usage_efficiency";
    }

    @Override
    public String description() {
        return "工具使用效率（LLM 判分，消耗 tokens）：检测过度调用/重复查询/绕圈取证；无工具调用时跳过";
    }

    @Override
    protected String criteria() {
        return "评估工具使用的效率与经济性：5=调用次数精炼，每次调用都贡献了新信息；"
                + "4=总体高效，个别冗余调用；3=存在明显的重复查询（相同信息查了多次）；"
                + "2=大量冗余调用或绕圈（先查 A 再绕回查 A）；1=过度调用严重失控。";
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
