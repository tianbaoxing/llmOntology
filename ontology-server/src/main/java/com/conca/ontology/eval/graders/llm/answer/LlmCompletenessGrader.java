package com.conca.ontology.eval.graders.llm.answer;

import com.conca.ontology.eval.graders.llm.LlmJudgeGrader;

import org.springframework.stereotype.Component;

import com.conca.ontology.eval.JudgeClient;

/**
 * 回答完整性评测（LLM-as-judge，对齐 OpenJudge ResponseCompletenessGrader）：
 * 评估「介绍文物」类开放问题的要点覆盖度（该说的说全没）。
 *
 * @author tianbaoxing
 * @date 2026-09-16
 */
@Component
public class LlmCompletenessGrader extends LlmJudgeGrader {

    public LlmCompletenessGrader(JudgeClient client) {
        super(client);
    }

    @Override
    public String name() {
        return "llm_response_completeness";
    }

    @Override
    public String description() {
        return "完整性（LLM 判分，消耗 tokens）：评估回答要点覆盖度，「介绍类」开放问题的必备指标";
    }

    @Override
    protected String criteria() {
        return "5=问题要点全覆盖无遗漏；4=基本覆盖；3=有可感知遗漏；2=明显缺失；1=严重缺失。"
                + "参考答案与补充评分细则中列出的要点应视为必须覆盖。";
    }
}
