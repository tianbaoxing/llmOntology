package com.conca.ontology.graph.model;
import java.util.List;

/** ontology-profile.json 文件结构（构建期问询记录，唯一事实源） */
public record ProfileFile(int version, List<ProfileEntry> items) {
}
