package com.conca.ontology.eval.graders.llm.dialogue;

import com.conca.ontology.eval.graders.llm.LlmJudgeGrader;

import org.springframework.stereotype.Component;

import com.conca.ontology.eval.GraderInput;
import com.conca.ontology.eval.JudgeClient;

/**
 * 指代消解评测（LLM-as-judge，对齐 OpenJudge AnaphoraResolutionGrader）：
 * 评估后续轮次中的代词（「它/这件/该文物」等）是否被正确消解到先前对话
 * 提及的对象。典型场景：先问「介绍一下 X」，再问「它是什么朝代的？」。
 * 需多轮样本（turns 格式）；单轮样本跳过。
 */
@Component
public class LlmAnaphoraResolutionGrader extends LlmJudgeGrader {

    public LlmAnaphoraResolutionGrader(JudgeClient client) {
        super(client);
    }

    @Override
    public String name() {
        return "llm_anaphora_resolution";
    }

    @Override
    public String description() {
        return "指代消解（LLM 判分，消耗 tokens）：后续轮次的代词是否正确指向前文对象（如「它/这件」）；单轮样本跳过";
    }

    @Override
    protected String criteria() {
        return "评估最后一轮问题中的代词/省略主语是否被正确消解：5=代词准确指向先前对话中的对象，"
                + "回答围绕该对象展开；4=消解正确但回答偶有跑偏；3=消解基本正确但信息混用（如把甲的属性安到乙上）；"
                + "2=消解错误，回答了别的对象；1=完全无视指代或答非所问。"
                + "若最后一轮问题无代词且主语明确，则按上下文衔接是否自然评分。";
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
