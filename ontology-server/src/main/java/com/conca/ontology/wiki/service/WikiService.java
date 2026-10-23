package com.conca.ontology.wiki.service;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.conca.ontology.file.dto.FileListDTO;
import com.conca.ontology.file.model.FileEntry;
import com.conca.ontology.file.service.FileStorageService;
import com.conca.ontology.file.service.MdConvertService;
import com.conca.ontology.shared.web.ApiException;
import com.conca.ontology.wiki.dto.WikiBySourceResultDTO;
import com.conca.ontology.wiki.dto.WikiDocResultDTO;
import com.conca.ontology.wiki.dto.WikiMetaUpdateResultDTO;
import com.conca.ontology.wiki.dto.WikiSearchResultDTO;
import com.conca.ontology.wiki.dto.WikiDocDTO;
import com.conca.ontology.wiki.dto.WikiListDTO;
import com.conca.ontology.wiki.model.WikiMetaEntry;
import com.conca.ontology.wiki.model.WikiMetadataFile;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Wiki 知识库：files-md/ 转换产物的只读呈现层（不做编辑，上传/删除/重转仍由 /files 独占管理）。
 * 文档清单 = FileStorageService 对账结果中 convertStatus=SUCCESS 的条目（与 LLM 可见性一致）；
 * 元信息 = MD 正文自动抽取（标题/发布时间/来源/作者，全角冒号匹配，避开首行半角"来源:"转换信息）
 *         + data/wiki-metadata.json 手工维护（分类/标签，以 mdName 为 key）。
 * 列表加载时顺带剔除孤儿元数据（对应 MD 已删除），文件删除无需联动通知。
 *
 * @author tianbaoxing
 * @date 2026-09-23
 */
@Service
public class WikiService {

    private static final Logger log = LoggerFactory.getLogger(WikiService.class);

    /** 正文元信息抽取（全角冒号；半角"来源: "仅出现在首行转换信息里，天然避开） */
    private static final Pattern P_DATE = Pattern.compile("发布时间：\\s*(\\d{4}-\\d{2}-\\d{2})");
    private static final Pattern P_SOURCE = Pattern.compile("来源：\\s*([^\\s，。；;|]+)");
    private static final Pattern P_AUTHOR = Pattern.compile("作者：\\s*([^\\s，。；;|]+)");
    private static final List<String> BINARY_EXTS = List.of(".pdf", ".docx", ".doc", ".xls", ".xlsx");

    /** 搜索加权：标题命中 ×40 > 元信息命中 ×20 > 正文单次命中 ×1（借鉴 WeKnora 搜索字段优先级） */
    private static final int SCORE_TITLE = 40;
    private static final int SCORE_META = 20;
    /** 搜索防御上限（结果篇数 / 每篇片段数 / 片段前后扩展字符数） */
    private static final int SEARCH_MAX_DOCS = 50;
    private static final int MAX_SNIPPETS = 3;
    private static final int SNIPPET_PAD = 40;

    private final FileStorageService fileStorageService;
    private final MdConvertService convertService;
    private final ObjectMapper mapper;
    private final WikiEntityService entityService;

    @Value("${app.wiki-metadata-file:data/wiki-metadata.json}")
    private String metadataFile;

    public WikiService(FileStorageService fileStorageService, MdConvertService convertService,
            ObjectMapper mapper, WikiEntityService entityService) {
        this.fileStorageService = fileStorageService;
        this.convertService = convertService;
        this.mapper = mapper;
        this.entityService = entityService;
    }

    /** 文档列表（先与文件元数据对账并清理孤儿，再逐篇抽取/合并元信息） */
    public synchronized WikiListDTO list() {
        List<FileEntry> entries = successEntries();
        Set<String> valid = Set.copyOf(entries.stream().map(FileEntry::mdName).toList());
        WikiMetadataFile mf = cleanup(loadMetadata(), valid);
        Map<String, WikiMetaEntry> manual = byMdName(mf);
        List<WikiDocDTO> docs = new ArrayList<>();
        for (FileEntry e : entries) {
            String content = readMd(e);
            docs.add(viewOf(e, content, manual.get(e.mdName())));
        }
        return new WikiListDTO(mf.version(), docs);
    }

    /** 单篇文档（MD 原文 + 合并元信息 + 正文命中的图谱实体） */
    public synchronized WikiDocResultDTO doc(String mdName) {
        FileEntry e = findSuccess(mdName, successEntries());
        WikiMetaEntry meta = byMdName(loadMetadata()).get(mdName);
        String content = readMd(e);
        String body = stripSourceLine(content);
        return new WikiDocResultDTO(e.mdName(), e.name(), extractTitle(body, e.mdName()),
                extract(P_DATE, body), extract(P_SOURCE, body), extract(P_AUTHOR, body),
                meta == null ? null : meta.category(), meta == null ? null : meta.tags(),
                e.convertedAt(), content,
                entityService.match(e.mdName(), e.convertedAt(), body));
    }

    /**
     * 全文搜索：逐篇读取 SUCCESS 的 MD 原文，toLower 包含匹配；
     * 加权分 = 标题命中 ×40 + 元信息（来源/分类/标签/作者/时间）命中 ×20 + 正文每次命中 ×1，降序。
     * 空白关键词返回空列表（不报错）；结果上限 50 篇。
     */
    public synchronized WikiSearchResultDTO search(String q) {
        String kw = q == null ? "" : q.strip().toLowerCase();
        if (kw.isEmpty()) {
            return new WikiSearchResultDTO(q, List.of());
        }
        List<FileEntry> entries = successEntries();
        Map<String, WikiMetaEntry> manual = byMdName(loadMetadata());
        List<SearchHit> hits = new ArrayList<>();
        for (FileEntry e : entries) {
            String content = readMd(e);
            String body = stripSourceLine(content);
            String lc = body.toLowerCase();
            long bodyHits = countOccurrences(lc, kw);
            String title = extractTitle(body, e.mdName());
            boolean titleHit = title != null && title.toLowerCase().contains(kw);
            WikiMetaEntry meta = manual.get(e.mdName());
            boolean metaHit = metaHit(meta, body, kw);
            if (!titleHit && !metaHit && bodyHits == 0) {
                continue;
            }
            long score = (titleHit ? SCORE_TITLE : 0) + (metaHit ? SCORE_META : 0) + bodyHits;
            hits.add(new SearchHit(score, bodyHits, new WikiSearchResultDTO.Doc(
                    e.mdName(), e.name(), title, extract(P_DATE, body), extract(P_SOURCE, body),
                    meta == null ? null : meta.category(), meta == null ? null : meta.tags(),
                    bodyHits, snippets(lc, body, kw))));
        }
        hits.sort(HIT_ORDER);
        List<WikiSearchResultDTO.Doc> docs = hits.stream()
                .limit(SEARCH_MAX_DOCS)
                .map(h -> h.doc)
                .toList();
        return new WikiSearchResultDTO(q, docs);
    }

    /** 反向溯源：按来源上传文件名查相关文档（两侧均源自 FileEntry.name，字符串相等即匹配） */
    public synchronized WikiBySourceResultDTO bySource(String file) {
        if (file == null || file.isBlank()) {
            throw new ApiException(400, "缺少 file 参数");
        }
        List<WikiDocDTO> docs = list().docs().stream()
                .filter(d -> file.equals(d.sourceName()))
                .toList();
        return new WikiBySourceResultDTO(docs);
    }

    /** 编辑元信息（category 置空、tags 置空即清除；孤儿清理后 upsert，version +1） */
    public synchronized WikiMetaUpdateResultDTO updateMeta(String mdName, String category, List<String> tags) {
        List<FileEntry> entries = successEntries();
        FileEntry e = findSuccess(mdName, entries);
        Set<String> valid = Set.copyOf(entries.stream().map(FileEntry::mdName).toList());
        WikiMetadataFile mf = cleanup(loadMetadata(), valid);
        WikiMetaEntry updated = new WikiMetaEntry(mdName, cleanCategory(category), cleanTags(tags));
        Map<String, WikiMetaEntry> byName = byMdName(mf);
        byName.put(mdName, updated);
        WikiMetadataFile next = new WikiMetadataFile(mf.version() + 1, new ArrayList<>(byName.values()));
        persist(next);
        log.info("wiki meta updated: {} -> category={}, tags={}", mdName, updated.category(), updated.tags());
        return new WikiMetaUpdateResultDTO(next.version(),
                viewOf(e, readMd(e), updated));
    }

    // ---------------- 内部 ----------------

    /** 搜索中间结构：加权分排序（标题/元信息命中的文档始终排在纯正文命中之前；同分按命中次数） */
    private record SearchHit(long score, long hits, WikiSearchResultDTO.Doc doc) {
    }

    private static final Comparator<SearchHit> HIT_ORDER =
            Comparator.comparingLong(SearchHit::score).reversed()
                    .thenComparing(Comparator.comparingLong(SearchHit::hits).reversed());

    /** 无重叠计数出现次数（kw 与文本均已 toLower） */
    private static long countOccurrences(String lcText, String kw) {
        long n = 0;
        int idx = 0;
        while ((idx = lcText.indexOf(kw, idx)) >= 0) {
            n++;
            idx += kw.length();
        }
        return n;
    }

    /** 元信息命中：手工维护（分类/标签）+ 正文抽取（来源/作者/发布时间）任一包含关键词 */
    private boolean metaHit(WikiMetaEntry meta, String body, String kw) {
        if (meta != null) {
            if (meta.category() != null && meta.category().toLowerCase().contains(kw)) {
                return true;
            }
            if (meta.tags() != null) {
                for (String t : meta.tags()) {
                    if (t != null && t.toLowerCase().contains(kw)) {
                        return true;
                    }
                }
            }
        }
        return containsIgnoreCase(extract(P_SOURCE, body), kw)
                || containsIgnoreCase(extract(P_AUTHOR, body), kw)
                || containsIgnoreCase(extract(P_DATE, body), kw);
    }

    private static boolean containsIgnoreCase(String s, String kw) {
        return s != null && s.toLowerCase().contains(kw);
    }

    /** 命中片段：以命中点为中心前后各 ~40 字符；相邻命中（相距 < 40 字符）合并为一段；每篇最多 3 段 */
    private static List<WikiSearchResultDTO.Snippet> snippets(String lc, String body, String kw) {
        List<int[]> ranges = new ArrayList<>();
        int idx = 0;
        while ((idx = lc.indexOf(kw, idx)) >= 0) {
            int end = idx + kw.length();
            if (!ranges.isEmpty() && idx - ranges.get(ranges.size() - 1)[1] < SNIPPET_PAD) {
                ranges.get(ranges.size() - 1)[1] = end;
            } else {
                ranges.add(new int[]{idx, end});
            }
            idx = end;
        }
        List<WikiSearchResultDTO.Snippet> out = new ArrayList<>(Math.min(MAX_SNIPPETS, ranges.size()));
        for (int i = 0; i < Math.min(MAX_SNIPPETS, ranges.size()); i++) {
            int[] r = ranges.get(i);
            int s = Math.max(0, r[0] - SNIPPET_PAD);
            int e = Math.min(body.length(), r[1] + SNIPPET_PAD);
            String text = (s > 0 ? "…" : "") + body.substring(s, e) + (e < body.length() ? "…" : "");
            out.add(new WikiSearchResultDTO.Snippet(text));
        }
        return out;
    }

    /** SUCCESS 条目（保持文件列表顺序）；顺带触发文件对账与 MD 转换补齐 */
    private List<FileEntry> successEntries() {
        FileListDTO fv = fileStorageService.list();
        return fv.files().stream()
                .filter(f -> FileEntry.STATUS_SUCCESS.equals(f.convertStatus()) && f.mdName() != null)
                .toList();
    }

    private FileEntry findSuccess(String mdName, List<FileEntry> entries) {
        if (mdName == null || mdName.isBlank() || mdName.contains("..")) {
            throw new ApiException(400, "非法文件名: " + mdName);
        }
        return entries.stream()
                .filter(f -> f.mdName().equals(mdName))
                .findFirst()
                .orElseThrow(() -> new ApiException(404, "文档不存在: " + mdName));
    }

    /** 剔除孤儿元数据（MD 已删除的条目） */
    private WikiMetadataFile cleanup(WikiMetadataFile mf, Set<String> valid) {
        List<WikiMetaEntry> kept = mf.entries().stream()
                .filter(e -> valid.contains(e.mdName())).toList();
        if (kept.size() == mf.entries().size()) {
            return mf;
        }
        WikiMetadataFile next = new WikiMetadataFile(mf.version() + 1, kept);
        persist(next);
        log.info("wiki meta orphans removed: {} -> {}", mf.entries().size(), kept.size());
        return next;
    }

    private Map<String, WikiMetaEntry> byMdName(WikiMetadataFile mf) {
        Map<String, WikiMetaEntry> byName = new LinkedHashMap<>();
        for (WikiMetaEntry e : mf.entries()) {
            byName.put(e.mdName(), e);
        }
        return byName;
    }

    private WikiDocDTO viewOf(FileEntry e, String content, WikiMetaEntry meta) {
        String body = stripSourceLine(content);
        return new WikiDocDTO(e.mdName(), e.name(), extractTitle(body, e.mdName()),
                extract(P_DATE, body), extract(P_SOURCE, body), extract(P_AUTHOR, body),
                meta == null ? null : meta.category(), meta == null ? null : meta.tags(),
                e.description(), e.convertedAt(), mdSize(e.mdName()));
    }

    /** 读 MD 原文（路径来自文件元数据，非用户输入） */
    private String readMd(FileEntry e) {
        try {
            return Files.readString(convertService.mdPathOf(e.mdName()), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new ApiException(500, "读取 MD 失败: " + e.mdName() + "（" + ex.getMessage() + "）");
        }
    }

    /** 首行转换信息（"> 来源: xxx · 转换于 xxx"）剔除后再抽取 */
    private static String stripSourceLine(String md) {
        if (md.startsWith("> 来源:") || md.startsWith(">来源:")) {
            int idx = md.indexOf('\n');
            return idx < 0 ? "" : md.substring(idx + 1);
        }
        return md;
    }

    /** 标题抽取：# 标题 → **加粗** → 首个普通文本行（剥尾部 setext 下划线）→ 文件名（去 .md 与二进制扩展名） */
    private static String extractTitle(String body, String mdName) {
        for (String raw : body.split("\n", -1)) {
            String t = raw.trim();
            if (t.isEmpty() || t.startsWith(">") || t.startsWith("***")
                    || t.startsWith("<br") || t.startsWith("---") || t.startsWith("![")) {
                continue;
            }
            if (t.startsWith("#")) {
                String h = t.replaceFirst("^#+\\s*", "").trim();
                if (!h.isEmpty()) {
                    return cap(h);
                }
                continue;
            }
            if (t.startsWith("**") && t.endsWith("**") && t.length() >= 5) {
                return cap(t.substring(2, t.length() - 2).trim());
            }
            String plain = t.replaceAll("[\\s\\-]+$", "").replace("\\|", "|").trim();
            if (!plain.isEmpty() && plain.length() <= 60) {
                return cap(plain);
            }
            return fallbackTitle(mdName);
        }
        return fallbackTitle(mdName);
    }

    private static String extract(Pattern p, String body) {
        Matcher m = p.matcher(body);
        return m.find() ? cap(m.group(1)) : null;
    }

    /** 清洗：去空白 + 限长 80 */
    private static String cap(String s) {
        s = s.strip();
        return s.length() > 80 ? s.substring(0, 80) : s;
    }

    private static String fallbackTitle(String mdName) {
        String n = mdName.endsWith(".md") ? mdName.substring(0, mdName.length() - 3) : mdName;
        String lower = n.toLowerCase();
        for (String ext : BINARY_EXTS) {
            if (lower.endsWith(ext)) {
                return n.substring(0, n.length() - ext.length());
            }
        }
        return n;
    }

    private static String cleanCategory(String c) {
        if (c == null) {
            return null;
        }
        c = c.strip();
        return c.isEmpty() ? null : c;
    }

    private static List<String> cleanTags(List<String> tags) {
        if (tags == null) {
            return null;
        }
        List<String> out = tags.stream().map(String::strip).filter(s -> !s.isEmpty()).distinct().toList();
        return out.isEmpty() ? null : out;
    }

    private long mdSize(String mdName) {
        try {
            return Files.size(convertService.mdPathOf(mdName));
        } catch (IOException e) {
            return 0;
        }
    }

    // ---------------- 元数据存取 ----------------

    private void persist(WikiMetadataFile mf) {
        try {
            Path file = metadataPath();
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), mf);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            throw new IllegalStateException("failed to save wiki metadata: " + metadataFile, e);
        }
    }

    private WikiMetadataFile loadMetadata() {
        Path file = metadataPath();
        if (Files.notExists(file)) {
            return WikiMetadataFile.empty();
        }
        try {
            return mapper.readValue(file.toFile(), WikiMetadataFile.class);
        } catch (Exception e) {
            throw new IllegalStateException("failed to load wiki metadata: " + file.toAbsolutePath(), e);
        }
    }

    private Path metadataPath() {
        return Path.of(metadataFile).toAbsolutePath();
    }
}
