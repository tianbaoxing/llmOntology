package com.conca.ontology.file.dto.view;
import java.util.List;

import com.conca.ontology.file.model.FileEntry;

import io.swagger.v3.oas.annotations.media.Schema;

/** 上传文件全量列表（与磁盘对账后返回） */
@Schema(description = "上传文件全量列表（与磁盘对账后返回）")
public record FileListView(
        @Schema(description = "文件列表（含绝对路径，LLM 按路径用自身工具读取内容）") List<FileEntry> files) {
}
