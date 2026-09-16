package com.conca.ontology.eval;

/**
 * 评测器接口（对齐 OpenJudge BaseGrader 的最小闭环）：
 * 输入 = GraderInput 参数包（EvalService 按 mapper 映射组装，grader 不感知数据集 schema），
 * 输出 = GraderScore。实现分两类：type=rule（本地算法判分，零成本确定性）/ type=llm（二期 LLM-as-judge）。
 */
public interface Grader {

    /** 评测器标识（数据集 graders 字段引用此名） */
    String name();

    /** 用途描述（页面勾选时展示） */
    String description();

    /** 实现类型：rule | llm */
    default String type() {
        return "rule";
    }

    /** 判分：参数由 GraderInput 按名取值；实现内部异常由 Runner 捕获转 GraderScore.fail */
    GraderScore evaluate(GraderInput input);
}
