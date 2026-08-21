package com.conca.ontology.model;

import io.swagger.v3.oas.annotations.media.Schema;

/** 上传文件条目：元数据 file-metadata.json 的单元 */
@Schema(description = "上传文件条目：名称、绝对路径、大小、类型、说明、上传时间")
public record FileEntry(
        @Schema(description = "文件名（相对 upload-dir，'/' 分隔）", example = "artifact.csv") String name,
        @Schema(description = "绝对路径（LLM 用此路径读取文件内容）",
                example = "C:\\me\\kaiyuan\\history\\llmOntology\\files\\artifact.csv") String path,
        @Schema(description = "文件大小（字节）", example = "2705445") long size,
        @Schema(description = "扩展名（小写，无点）", example = "csv") String ext,
        @Schema(description = "文件说明（可为空字符串）", example = "中国国家博物馆藏品信息") String description,
        @Schema(description = "上传时间", example = "2026-08-20 11:29:52") String uploadedAt) {
}
