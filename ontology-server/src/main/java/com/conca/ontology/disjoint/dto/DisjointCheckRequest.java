package com.conca.ontology.disjoint.dto;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "冲突检测请求：传入一段待校验的文本（LLM 拟输出 / 草稿 / 抽取结果等）")
public record DisjointCheckRequest(
        @Schema(description = "待检测文本。若为空则返回 0 冲突。例：\"这件器物是唐代青铜青花瓷\"",
                example = "这件器物是唐代青铜青花瓷") String text) {
}
