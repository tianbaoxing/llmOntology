package com.conca.ontology.eval;

/**
 * 自定义 LLM 评测器定义（用户在页面输入，无需写代码）：
 * - criteria 模式（默认）：prompt 仅写「怎么打分」的评分标准，框架固定拼接
 *   【问题】【待评估回答】等区块（样本内容隔离，天然防提示词注入）；
 * - template 模式（进阶）：prompt 为完整模板，可用五占位符
 *   {{query}}/{{answer}}/{{reference}}/{{context}}/{{rubric}}。
 * 持久化于 data/eval/custom-graders.json（CustomGraderStore），
 * 运行时包装为 CustomLlmGrader（LlmJudgeGrader 子类）动态注册进 EvalService。
 */
public record CustomGraderDefinition(
        String name,
        String description,
        String mode,
        String prompt,
        String createdAt,
        String updatedAt) {

    public static final String MODE_CRITERIA = "criteria";
    public static final String MODE_TEMPLATE = "template";
}
