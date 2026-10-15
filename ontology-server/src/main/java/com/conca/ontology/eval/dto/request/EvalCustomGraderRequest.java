package com.conca.ontology.eval.dto.request;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * 自定义评测器创建/更新请求。
 * mode=criteria（默认）：prompt 仅写评分标准，样本内容由框架固定区块隔离拼接；
 * mode=template：prompt 为完整模板，可用五占位符 {{query}}/{{answer}}/{{reference}}/{{context}}/{{rubric}}。
 * 深度校验（名称格式/占位符白名单/长度）见 CustomGraderStore.validate。
 *
 * @author tianbaoxing
 * @date 2026-09-01
 */
@Schema(description = "自定义评测器创建/更新请求（用户提示词驱动的 LLM 评测器）")
public record EvalCustomGraderRequest(
        @Schema(description = "评测器名（3-32 位小写字母/数字/下划线；更新时须与路径一致）", example = "my_concise_check")
        String name,
        @Schema(description = "用途描述（页面悬停展示）", example = "回答是否简洁不啰嗦")
        String description,
        @Schema(description = "提示词模式：criteria=评分标准（默认）| template=完整模板（五占位符）", example = "criteria")
        String mode,
        @Schema(description = "提示词（criteria=评分标准；template=含 {{answer}} 等占位符的完整模板）")
        @NotBlank(message = "prompt 不能为空") String prompt) {
}
