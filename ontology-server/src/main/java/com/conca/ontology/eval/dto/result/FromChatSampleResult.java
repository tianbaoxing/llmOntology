package com.conca.ontology.eval.dto.result;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 聊天记录沉淀：从指定会话提取的问答对（仅提取预填表单，不落盘；
 * 期望回答由人工补齐后走新增样本接口 POST /api/eval/datasets/{name}/samples）。
 *
 * @author tianbaoxing
 * @date 2026-09-02
 */
@Schema(description = "聊天记录沉淀：会话问答对提取结果")
public record FromChatSampleResult(
        @Schema(description = "会话 ID", example = "s-20260901-101530-abc") String sessionId,
        @Schema(description = "AI 回答在消息数组中的下标（从 0 起）", example = "5") int msgIndex,
        @Schema(description = "对应的用户提问（最近一条 user 消息）") String query,
        @Schema(description = "AI 回答全文") String answer,
        @Schema(description = "消息时间戳") String ts,
        @Schema(description = "该问答是会话第几问（从 1 起）", example = "2") int turnIndex,
        @Schema(description = "会话消息总数", example = "6") int messageCount) {
}
