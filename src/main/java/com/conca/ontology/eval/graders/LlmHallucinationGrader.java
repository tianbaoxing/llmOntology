package com.conca.ontology.eval.graders;

import org.springframework.stereotype.Component;

import com.conca.ontology.eval.JudgeClient;

/**
 * 幻觉检测评测（LLM-as-judge，对齐 OpenJudge HallucinationGrader）：
 * 以证据上下文（轨迹中图谱查询返回）与参考答案核查回答是否编造无依据信息。
 * 高分=无幻觉（反向指标），证据上下文由基类从轨迹自动注入。
 */
@Component
public class LlmHallucinationGrader extends LlmJudgeGrader {

    public LlmHallucinationGrader(JudgeClient client) {
        super(client);
    }

    @Override
    public String name() {
        return "llm_hallucination";
    }

    @Override
    public String description() {
        return "幻觉检测（LLM 判分，消耗 tokens）：以图谱查询证据核查回答是否编造无依据信息，高分=无幻觉";
    }

    @Override
    protected String criteria() {
        return "评估回答是否编造无依据信息（高分=无幻觉）：5=全部事实均被参考答案或证据上下文支持；"
                + "4=个别表述无出处但无害；3=主体有据但个别细节存疑；2=较多内容无依据；1=大量编造。";
    }
}
