package com.conca.ontology.wiki.model;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Wiki 元数据文件：data/wiki-metadata.json 的整体结构（version 每次变更 +1，原子落盘）。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WikiMetadataFile(int version, List<WikiMetaEntry> entries) {

    public static WikiMetadataFile empty() {
        return new WikiMetadataFile(0, List.of());
    }
}
