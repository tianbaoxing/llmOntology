package com.conca.ontology.eval.graders;

import org.springframework.stereotype.Component;

import com.conca.ontology.eval.JudgeClient;

/**
 * 正确性评测（LLM-as-judge，对齐 OpenJudge CorrectnessGrader）：
 * 对照参考答案评估回答的事实正确性（朝代/窑口/出土地等事实核对）。
 * 原始 1-5 分归一化 0-1；无参考答案时依据证据上下文判断。
 */
@Component
public class LlmCorrectnessGrader extends LlmJudgeGrader {

    public LlmCorrectnessGrader(JudgeClient client) {
        super(client);
    }

    @Override
    public String name() {
        return "llm_correctness";
    }

    @Override
    public String description() {
        return "正确性（LLM 判分，消耗 tokens）：对照参考答案评估回答的事实正确性，1-5 分归一化为 0-1";
    }

    @Override
    protected String criteria() {
        return "5=与参考答案一致且无事实错误；4=基本正确仅细节出入；3=部分正确；2=多数错误；1=完全错误或编造。"
                + "无参考答案时依据证据上下文判断事实是否可靠。";
    }
}
