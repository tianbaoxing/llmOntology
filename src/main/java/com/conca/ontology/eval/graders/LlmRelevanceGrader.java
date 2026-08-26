package com.conca.ontology.eval.graders;

import org.springframework.stereotype.Component;

import com.conca.ontology.eval.JudgeClient;

/**
 * 相关性评测（LLM-as-judge，对齐 OpenJudge RelevanceGrader）：
 * 检测回答是否跑题、答非所问。原始 1-5 分归一化 0-1。
 */
@Component
public class LlmRelevanceGrader extends LlmJudgeGrader {

    public LlmRelevanceGrader(JudgeClient client) {
        super(client);
    }

    @Override
    public String name() {
        return "llm_relevance";
    }

    @Override
    public String description() {
        return "相关性（LLM 判分，消耗 tokens）：评估回答与问题的切题程度，检测跑题与答非所问";
    }

    @Override
    protected String criteria() {
        return "5=切题且直接回答问题；4=切题但略有冗余；3=部分相关；2=大部分跑题；1=答非所问。";
    }
}
