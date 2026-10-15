package com.conca.ontology.eval.graders.llm.dialogue;

import com.conca.ontology.eval.graders.llm.LlmJudgeGrader;

import org.springframework.stereotype.Component;

import com.conca.ontology.eval.model.GraderInput;
import com.conca.ontology.eval.JudgeClient;

/**
 * 跨轮上下文记忆评测（LLM-as-judge，对齐 OpenJudge ContextMemoryGrader）：
 * 评估最后一轮回答是否正确利用了先前轮次已建立的信息（如首轮介绍过的文物），
 * 而不是把后续问题当作全新话题重新猜测。需多轮样本（turns 格式）；单轮样本跳过。
 *
 * @author tianbaoxing
 * @date 2026-09-16
 */
@Component
public class LlmContextMemoryGrader extends LlmJudgeGrader {

    public LlmContextMemoryGrader(JudgeClient client) {
        super(client);
    }

    @Override
    public String name() {
        return "llm_context_memory";
    }

    @Override
    public String description() {
        return "跨轮上下文记忆（LLM 判分，消耗 tokens）：最后一轮回答是否正确沿用先前轮次的信息；单轮样本跳过";
    }

    @Override
    protected String criteria() {
        return "评估最后一轮回答对先前轮次上下文的利用：5=正确沿用先前对话中的信息（主题/实体/已给结论），"
                + "无信息丢失与矛盾；4=基本沿用，个别细节需回看；3=部分利用，部分当作新话题重新回答；"
                + "2=基本忽略上下文，重复提问先前已给出的信息；1=完全当作新话题或与先前轮次矛盾。";
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
