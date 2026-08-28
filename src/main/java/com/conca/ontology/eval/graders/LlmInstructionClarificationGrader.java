package com.conca.ontology.eval.graders;

import org.springframework.stereotype.Component;

import com.conca.ontology.eval.GraderInput;
import com.conca.ontology.eval.JudgeClient;

/**
 * 主动澄清评测（LLM-as-judge，对齐 OpenJudge InstructionClarificationGrader）：
 * 当后续轮次问题模糊/指代不明（如「那件碗呢？」但前文提及多件碗）时，回答应
 * 主动澄清而非臆测；问题明确时正常回答不扣分。需多轮样本（turns 格式）；单轮样本跳过。
 */
@Component
public class LlmInstructionClarificationGrader extends LlmJudgeGrader {

    public LlmInstructionClarificationGrader(JudgeClient client) {
        super(client);
    }

    @Override
    public String name() {
        return "llm_instruction_clarification";
    }

    @Override
    public String description() {
        return "主动澄清（LLM 判分，消耗 tokens）：问题模糊/指代不明时应主动澄清而非臆测；单轮样本跳过";
    }

    @Override
    protected String criteria() {
        return "评估对模糊问题的处理方式：若最后一轮问题含糊或指代不明（前文有多个候选对象），"
                + "5=主动澄清请求（说明歧义并请用户明确）；4=给出澄清的同时按最可能对象作答并注明假设；"
                + "3=未澄清但选择了合理对象作答；2=未澄清且对象选择明显牵强；1=未澄清且臆测作答。"
                + "问题本身明确无歧义时：5=正常作答，不画蛇添足地反问。";
    }

    @Override
    protected String extraBlock(GraderInput input) {
        return renderDialogue(input.record());
    }

    @Override
    protected boolean requireMultiTurn() {
        return true;
    }
}
