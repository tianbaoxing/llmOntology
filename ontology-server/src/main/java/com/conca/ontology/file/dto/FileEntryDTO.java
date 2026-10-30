package com.conca.ontology.file.dto;
import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;

/** 上传文件条目：file_metadata 表行的内存视图，同时作为 API 出参（FileListDTO 等内嵌）。
 * 双身份声明：本类既是持久化中间模型（FileEntryConverter 行映射的唯一实现），又是前端 API 契约，
 * 字段演化 additive-only（只增不改名不改类型）。转换字段（mdName/convertStatus/convertMsg/convertedAt）
 * 为二期扩展：旧数据缺省反序列化为 null，由对账逻辑自动回填；NONE 表示文本类无需转换。
 *
 * @author tianbaoxing
 * @date 2026-08-20
 */
@Schema(description = "上传文件条目：名称、绝对路径、大小、类型、说明、上传时间、MD 转换信息")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FileEntryDTO(
        @Schema(description = "文件名（相对 upload-dir，'/' 分隔）", example = "artifact.csv") String name,
        @Schema(description = "绝对路径（LLM 用此路径读取文件内容）",
                example = "C:\\me\\kaiyuan\\history\\llmOntology\\files\\artifact.csv") String path,
        @Schema(description = "文件大小（字节）", example = "2705445") long size,
        @Schema(description = "扩展名（小写，无点）", example = "csv") String ext,
        @Schema(description = "文件说明（可为空字符串）", example = "国家博物馆藏品信息") String description,
        @Schema(description = "上传时间", example = "2026-08-20 11:29:52") String uploadedAt,
        @Schema(description = "对应 Markdown 文件名（仅二进制转换文件有值，如 报告.docx.md）",
                example = "报告.docx.md") String mdName,
        @Schema(description = "转换状态：NONE（文本类无需转换）/ SUCCESS / FAILED") String convertStatus,
        @Schema(description = "转换失败原因（仅 FAILED 时有值）") String convertMsg,
        @Schema(description = "转换完成时间", example = "2026-09-14 10:00:00") String convertedAt) {

    public static final String STATUS_NONE = "NONE";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";
}
