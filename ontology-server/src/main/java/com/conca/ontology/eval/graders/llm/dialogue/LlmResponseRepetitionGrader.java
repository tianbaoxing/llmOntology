package com.conca.ontology.eval.graders.llm.dialogue;

import com.conca.ontology.eval.graders.llm.LlmJudgeGrader;

import org.springframework.stereotype.Component;

import com.conca.ontology.eval.model.GraderInput;
import com.conca.ontology.eval.JudgeClient;

/**
 * 跨轮回答重复评测（LLM-as-judge，OpenJudge ResponseRepetitionGrader 的多轮版）：
 * 评估最后一轮回答是否只是复述先前轮次内容而无新信息（换汤不换药的重复）。
 * 需多轮样本（turns 格式）；单轮样本跳过（单轮内的字面重复由规则版 response_repetition 覆盖）。
 */
@Component
public class LlmResponseRepetitionGrader extends LlmJudgeGrader {

    public LlmResponseRepetitionGrader(JudgeClient client) {
        super(client);
    }

    @Override
    public String name() {
        return "llm_response_repetition";
    }

    @Override
    public String description() {
        return "跨轮回答重复（LLM 判分，消耗 tokens）：最后一轮是否复述先前轮次内容而无新信息；单轮样本跳过";
    }

    @Override
    protected String criteria() {
        return "评估最后一轮回答相对先前轮次的信息增量：5=针对新问题给出了新信息，无复述；"
                + "4=以新信息为主，仅有必要的少量回顾；3=新旧信息各半；"
                + "2=大部分是先前轮次内容的复述；1=完全重复先前回答，无任何新信息。";
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
