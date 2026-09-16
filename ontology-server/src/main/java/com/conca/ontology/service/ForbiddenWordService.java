package com.conca.ontology.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.conca.ontology.exception.ApiException;
import com.conca.ontology.model.ForbiddenWordFile;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.sourceforge.pinyin4j.PinyinHelper;
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType;
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat;
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType;
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType;
import net.sourceforge.pinyin4j.format.exception.BadHanyuPinyinOutputFormatCombination;

@Service
public class ForbiddenWordService {

    private static final Logger log = LoggerFactory.getLogger(ForbiddenWordService.class);

    private final ObjectMapper mapper;

    @Value("${app.forbidden-words-file:data/forbidden-words.json}")
    private String forbiddenWordsFile;

    @Value("${app.forbidden-words.homophone-enabled:true}")
    private boolean homophoneEnabled;

    @Value("${app.forbidden-words.homophone-tone-match:false}")
    private boolean homophoneToneMatch;

    private final Map<String, String> pinyinCache = new HashMap<>();

    private static final HanyuPinyinOutputFormat WITH_TONE_FORMAT;
    private static final HanyuPinyinOutputFormat WITHOUT_TONE_FORMAT;

    static {
        WITH_TONE_FORMAT = new HanyuPinyinOutputFormat();
        WITH_TONE_FORMAT.setCaseType(HanyuPinyinCaseType.LOWERCASE);
        WITH_TONE_FORMAT.setToneType(HanyuPinyinToneType.WITH_TONE_NUMBER);
        WITH_TONE_FORMAT.setVCharType(HanyuPinyinVCharType.WITH_V);

        WITHOUT_TONE_FORMAT = new HanyuPinyinOutputFormat();
        WITHOUT_TONE_FORMAT.setCaseType(HanyuPinyinCaseType.LOWERCASE);
        WITHOUT_TONE_FORMAT.setToneType(HanyuPinyinToneType.WITHOUT_TONE);
        WITHOUT_TONE_FORMAT.setVCharType(HanyuPinyinVCharType.WITH_V);
    }

    public ForbiddenWordService(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public ForbiddenWordFile loadAll() {
        Path file = filePath();
        if (Files.notExists(file)) {
            return new ForbiddenWordFile(0, List.of());
        }
        ForbiddenWordFile result = read(file);
        rebuildPinyinCache(result.words());
        return result;
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
        cachePinyinForWord(trimmed);
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
        String nw = normalize(word);
        pinyinCache.remove(nw);
        pinyinCache.remove(nw + "__t");
        log.info("forbidden word deleted: {} (version {})", word, next.version());
        return next;
    }

    /**
     * 检查文本中包含哪些禁用词。
     * 先对用户输入进行归一化：去除全部空格、换行、制表符，以及常见中英文标点符号和特殊字符，
     * 然后在归一化后的字符串中做子串包含匹配，命中的禁用词原样返回（返回未归一化的原词）。
     * 若开启同音词检测，会进一步在拼音层面做子串匹配。
     */
    public List<String> check(String text) {
        List<String> hits = new ArrayList<>();
        if (text == null || text.isBlank()) return hits;
        String normalized = normalize(text);
        if (normalized.isEmpty()) return hits;

        ForbiddenWordFile all = loadAll();

        String textPinyin = null;
        if (homophoneEnabled) {
            textPinyin = toPinyin(normalized, homophoneToneMatch);
        }

        for (String w : all.words()) {
            String nw = normalize(w);
            if (!nw.isEmpty() && normalized.contains(nw)) {
                hits.add(w);
                continue;
            }
            if (homophoneEnabled && textPinyin != null && !textPinyin.isEmpty()) {
                String cacheKey = homophoneToneMatch ? nw + "__t" : nw;
                String wp = pinyinCache.get(cacheKey);
                if (wp == null) {
                    wp = toPinyin(nw, homophoneToneMatch);
                    pinyinCache.put(cacheKey, wp);
                }
                if (!wp.isEmpty() && textPinyin.contains(wp)) {
                    hits.add(w);
                }
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

    private void rebuildPinyinCache(List<String> words) {
        pinyinCache.clear();
        for (String w : words) {
            cachePinyinForWord(w);
        }
    }

    private void cachePinyinForWord(String word) {
        String nw = normalize(word);
        if (nw.isEmpty()) return;
        String withTone = toPinyin(nw, true);
        String withoutTone = toPinyin(nw, false);
        pinyinCache.put(nw + "__t", withTone);
        pinyinCache.put(nw, withoutTone);
    }

    static String toPinyin(String text, boolean withTone) {
        if (text == null || text.isEmpty()) return "";
        HanyuPinyinOutputFormat format = withTone ? WITH_TONE_FORMAT : WITHOUT_TONE_FORMAT;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c <= 127) {
                sb.append(c);
                continue;
            }
            try {
                String[] arr = PinyinHelper.toHanyuPinyinStringArray(c, format);
                if (arr != null && arr.length > 0) {
                    sb.append(arr[0]);
                } else {
                    sb.append(c);
                }
            } catch (BadHanyuPinyinOutputFormatCombination e) {
                sb.append(c);
            }
        }
        return sb.toString();
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
