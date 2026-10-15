package com.conca.ontology.eval.dto.request;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * 创建评测任务请求：dataset 为数据集文件名（data/eval/datasets/ 下）；
 * graders 为启用的评测器名列表（评测器仅在此任务级选择，样本不指定；至少勾选一个）；
 * judgeRuns 为 LLM 判分次数运行参数（1-3，缺省/null 取配置 app.eval.judge.runs），
 * >1 时多次判分取平均降噪（B8 多次评测策略），仅对 LLM 评测器生效。
 *
 * @author tianbaoxing
 * @date 2026-08-26
 */
@Schema(description = "创建评测任务请求")
public record EvalRunRequest(
        @Schema(description = "数据集文件名（如 demo.jsonl）", example = "demo.jsonl")
        @NotBlank(message = "dataset 不能为空") String dataset,
        @Schema(description = "启用的评测器名列表（必选至少一个）", example = "[\"string_contains\",\"number_accuracy\"]")
        List<String> graders,
        @Schema(description = "LLM 判分次数（1-3；>1 时多次判分取平均降噪，仅 LLM 评测器生效；缺省取服务端配置）", example = "2")
        Integer judgeRuns) {
}
