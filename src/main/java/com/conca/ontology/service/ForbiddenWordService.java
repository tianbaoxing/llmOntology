package com.conca.ontology.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.conca.ontology.exception.ApiException;
import com.conca.ontology.model.ForbiddenWordFile;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class ForbiddenWordService {

    private static final Logger log = LoggerFactory.getLogger(ForbiddenWordService.class);

    private final ObjectMapper mapper;

    @Value("${app.forbidden-words-file:data/forbidden-words.json}")
    private String forbiddenWordsFile;

    public ForbiddenWordService(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public ForbiddenWordFile loadAll() {
        Path file = filePath();
        if (Files.notExists(file)) {
            return new ForbiddenWordFile(0, List.of());
        }
        return read(file);
    }

    public synchronized ForbiddenWordFile add(String word) {
        if (word == null || word.isBlank()) {
            throw new ApiException(400, "禁用词不能为空");
        }
        String trimmed = word.trim();
        Path file = filePath();
        ForbiddenWordFile pf = Files.exists(file) ? read(file) : new ForbiddenWordFile(0, List.of());
        List<String> words = new ArrayList<>(pf.words());
        if (words.contains(trimmed)) {
            throw new ApiException(409, "禁用词已存在: " + trimmed);
        }
        words.add(trimmed);
        ForbiddenWordFile next = new ForbiddenWordFile(pf.version() + 1, words);
        write(file, next);
        log.info("forbidden word added: {} (version {})", trimmed, next.version());
        return next;
    }

    public synchronized ForbiddenWordFile delete(String word) {
        if (word == null || word.isBlank()) {
            throw new ApiException(400, "禁用词不能为空");
        }
        Path file = filePath();
        ForbiddenWordFile pf = Files.exists(file) ? read(file) : new ForbiddenWordFile(0, List.of());
        List<String> words = new ArrayList<>(pf.words());
        boolean removed = words.remove(word);
        if (!removed) throw new ApiException(404, "禁用词不存在: " + word);
        ForbiddenWordFile next = new ForbiddenWordFile(pf.version() + 1, words);
        write(file, next);
        log.info("forbidden word deleted: {} (version {})", word, next.version());
        return next;
    }

    /**
     * 检查文本中包含哪些禁用词。
     * 先对用户输入进行归一化：去除全部空格、换行、制表符，以及常见中英文标点符号和特殊字符，
     * 然后在归一化后的字符串中做子串包含匹配，命中的禁用词原样返回（返回未归一化的原词）。
     */
    public List<String> check(String text) {
        List<String> hits = new ArrayList<>();
        if (text == null || text.isBlank()) return hits;
        String normalized = normalize(text);
        if (normalized.isEmpty()) return hits;
        ForbiddenWordFile all = loadAll();
        for (String w : all.words()) {
            String nw = normalize(w);
            if (!nw.isEmpty() && normalized.contains(nw)) {
                hits.add(w);
            }
        }
        return hits;
    }

    /**
     * 归一化：移除所有空白字符（空格/换行/制表符）以及中英文标点和特殊字符，
     * 只保留中英文与数字字母，统一转为小写后返回。
     */
    public static String normalize(String text) {
        if (text == null) return "";
        return text.replaceAll("[\\s\\p{P}\\p{S}\\p{C}]+", "").toLowerCase();
    }

    private ForbiddenWordFile read(Path file) {
        try {
            return mapper.readValue(file.toFile(), ForbiddenWordFile.class);
        } catch (Exception e) {
            throw new IllegalStateException("failed to load forbidden words file: " + file.toAbsolutePath(), e);
        }
    }

    private void write(Path file, ForbiddenWordFile next) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), next);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            throw new IllegalStateException("failed to save forbidden words file: " + file.toAbsolutePath(), e);
        }
    }

    private Path filePath() {
        return Path.of(forbiddenWordsFile).toAbsolutePath();
    }
}
