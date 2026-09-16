package com.conca.ontology.model;

import java.util.List;

/** data/file-metadata.json 文件结构（上传文件元数据，唯一事实源） */
public record FileMetadataFile(int version, List<FileEntry> files) {
}
