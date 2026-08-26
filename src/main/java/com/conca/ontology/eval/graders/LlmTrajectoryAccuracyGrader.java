package com.conca.ontology.eval.graders;

import org.springframework.stereotype.Component;

import com.conca.ontology.eval.GraderInput;
import com.conca.ontology.eval.JudgeClient;

/**
 * 轨迹准确性评测（LLM-as-judge，对齐 OpenJudge TrajectoryAccuracyGrader）：
 * 评估整条轨迹是否达成提问目标（高效达成/达成但绕路/失败），直接反映本次问答全程质量。
 * 无工具调用时跳过（不消耗 judge 调用）。
 */
@Component
public class LlmTrajectoryAccuracyGrader extends LlmJudgeGrader {

    public LlmTrajectoryAccuracyGrader(JudgeClient client) {
        super(client);
    }

    @Override
    public String name() {
        return "llm_trajectory_accuracy";
    }

    @Override
    public String description() {
        return "轨迹准确性（LLM 判分，消耗 tokens）：评估整条问答轨迹是否高效达成目标；无工具调用时跳过";
    }

    @Override
    protected String criteria() {
        return "综合问题、最终回答与工具调用轨迹评估整体效果：5=高效达成（步骤精炼）；4=达成但略有绕路；"
                + "3=达成但明显绕路；2=勉强接近目标；1=未达成。";
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
