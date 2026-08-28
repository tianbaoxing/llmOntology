package com.conca.ontology.eval.graders;

import org.springframework.stereotype.Component;

import com.conca.ontology.eval.JudgeClient;

/**
 * 指令遵循评测（LLM-as-judge，对齐 OpenJudge InstructionFollowingGrader）：
 * 评估回答是否遵循问题或评分细则中的显式约束（如「仅回答朝代」「不超过两句」
 * 「用 JSON 格式」等）；无显式约束时评估回答是否切题且不画蛇添足。
 */
@Component
public class LlmInstructionFollowingGrader extends LlmJudgeGrader {

    public LlmInstructionFollowingGrader(JudgeClient client) {
        super(client);
    }

    @Override
    public String name() {
        return "llm_instruction_following";
    }

    @Override
    public String description() {
        return "指令遵循（LLM 判分，消耗 tokens）：评估回答是否遵循问题/评分细则中的格式与约束要求，1-5 分归一化为 0-1";
    }

    @Override
    protected String criteria() {
        return "评估回答对显式指令的遵循程度：问题或【补充评分细则】中若含格式/字数/范围等约束"
                + "（如「仅回答朝代」「不超过两句」），5=完全遵循全部约束；4=基本遵循仅轻微越界；"
                + "3=部分遵循；2=多数约束未遵循；1=完全无视约束。"
                + "无显式约束时：5=切题回答且不夹带无关内容；3=切题但冗余；1=答非所问或自行编造约束外的输出。";
    }
}
