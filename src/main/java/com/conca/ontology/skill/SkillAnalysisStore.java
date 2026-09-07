package com.conca.ontology.skill;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.conca.ontology.exception.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;

/**
 * 技能分析结果持久层：data/skill-analysis.json（单文件对象，skills 按技能名索引）。
 * 启动时加载（文件损坏 fail-fast，避免空 map 覆盖保存造成历史分析丢失），
 * 保存走 tmp + 原子 rename（对齐 CustomGraderStore 落盘策略）。
 * 分析结果不缓存于内存之外：每次保存全量写盘，数据量极小（每技能一个对象）。
 */
@Component
public class SkillAnalysisStore {

    private static final Logger log = LoggerFactory.getLogger(SkillAnalysisStore.class);

    private final ObjectMapper mapper;
    private final Map<String, SkillAnalysis> data = new LinkedHashMap<>();

    /** 默认 data/skill-analysis.json（相对 cwd）；可用 app.skill-analysis-file 覆盖 */
    @Value("${app.skill-analysis-file:data/skill-analysis.json}")
    private String file;

    public SkillAnalysisStore(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @PostConstruct
    void load() {
        Path p = Path.of(file);
        if (!Files.isRegularFile(p)) {
            return; // 尚无分析结果
        }
        try {
            JsonNode root = mapper.readTree(p.toFile());
            JsonNode skills = root.path("skills");
            if (skills.isObject()) {
                skills.fields().forEachRemaining(e ->
                        data.put(e.getKey(), mapper.convertValue(e.getValue(), SkillAnalysis.class)));
            }
            log.info("loaded {} skill analysis from {}", data.size(), file);
        } catch (IOException e) {
            // fail-fast：损坏文件若静默清空，下次保存会把已有分析全部覆盖丢失
            throw new IllegalStateException("技能分析文件解析失败（" + file + "）: " + e.getMessage(), e);
        }
    }

    public synchronized SkillAnalysis get(String name) {
        return data.get(name);
    }

    public synchronized Map<String, SkillAnalysis> all() {
        return new LinkedHashMap<>(data);
    }

    public synchronized void put(String name, SkillAnalysis analysis) {
        data.put(name, analysis);
        persist();
    }

    private void persist() {
        Path p = Path.of(file);
        try {
            Files.createDirectories(p.toAbsolutePath().getParent());
            Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
            mapper.writerWithDefaultPrettyPrinter()
                    .writeValue(tmp.toFile(), Map.of("version", 1, "skills", data));
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new ApiException(500, "写入技能分析文件失败: " + e.getMessage());
        }
    }
}
