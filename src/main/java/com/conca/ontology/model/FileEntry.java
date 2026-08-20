package com.conca.ontology.model;

/** 上传文件条目：元数据 file-metadata.json 的单元 */
public record FileEntry(
        String name,
        String path,
        long size,
        String ext,
        String description,
        String uploadedAt) {
}
