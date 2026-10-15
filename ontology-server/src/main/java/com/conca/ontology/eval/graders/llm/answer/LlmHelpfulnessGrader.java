package com.conca.ontology.eval.graders.llm.answer;

import com.conca.ontology.eval.graders.llm.LlmJudgeGrader;

import org.springframework.stereotype.Component;

import com.conca.ontology.eval.JudgeClient;

/**
 * 回答有用性评测（LLM-as-judge，对齐 OpenJudge ResponseHelpfulnessGrader）：
 * 评估回答对提问者的实际帮助程度，与完整性成对使用。
 *
 * @author tianbaoxing
 * @date 2026-09-16
 */
@Component
public class LlmHelpfulnessGrader extends LlmJudgeGrader {

    public LlmHelpfulnessGrader(JudgeClient client) {
        super(client);
    }

    @Override
    public String name() {
        return "llm_response_helpfulness";
    }

    @Override
    public String description() {
        return "有用性（LLM 判分，消耗 tokens）：评估回答对提问者的实际帮助程度";
    }

    @Override
    protected String criteria() {
        return "5=信息充足、直接可用；4=基本有用；3=帮助有限；2=几乎无帮助；1=无帮助。";
    }
}
