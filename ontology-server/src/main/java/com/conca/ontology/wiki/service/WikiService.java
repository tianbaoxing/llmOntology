package com.conca.ontology.wiki.service;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
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

import com.conca.ontology.file.dto.view.FileListView;
import com.conca.ontology.file.model.FileEntry;
import com.conca.ontology.file.service.FileStorageService;
import com.conca.ontology.file.service.MdConvertService;
import com.conca.ontology.shared.web.ApiException;
import com.conca.ontology.wiki.dto.result.WikiDocResult;
import com.conca.ontology.wiki.dto.result.WikiMetaUpdateResult;
import com.conca.ontology.wiki.dto.view.WikiDocView;
import com.conca.ontology.wiki.dto.view.WikiListView;
import com.conca.ontology.wiki.model.WikiMetaEntry;
import com.conca.ontology.wiki.model.WikiMetadataFile;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Wiki 知识库：files-md/ 转换产物的只读呈现层（不做编辑，上传/删除/重转仍由 /files 独占管理）。
 * 文档清单 = FileStorageService 对账结果中 convertStatus=SUCCESS 的条目（与 LLM 可见性一致）；
 * 元信息 = MD 正文自动抽取（标题/发布时间/来源/作者，全角冒号匹配，避开首行半角"来源:"转换信息）
 *         + data/wiki-metadata.json 手工维护（分类/标签，以 mdName 为 key）。
 * 列表加载时顺带剔除孤儿元数据（对应 MD 已删除），文件删除无需联动通知。
 */
@Service
public class WikiService {

    private static final Logger log = LoggerFactory.getLogger(WikiService.class);

    /** 正文元信息抽取（全角冒号；半角"来源: "仅出现在首行转换信息里，天然避开） */
    private static final Pattern P_DATE = Pattern.compile("发布时间：\\s*(\\d{4}-\\d{2}-\\d{2})");
    private static final Pattern P_SOURCE = Pattern.compile("来源：\\s*([^\\s，。；;|]+)");
    private static final Pattern P_AUTHOR = Pattern.compile("作者：\\s*([^\\s，。；;|]+)");
    private static final List<String> BINARY_EXTS = List.of(".pdf", ".docx", ".doc", ".xls", ".xlsx");

    private final FileStorageService fileStorageService;
    private final MdConvertService convertService;
    private final ObjectMapper mapper;

    @Value("${app.wiki-metadata-file:data/wiki-metadata.json}")
    private String metadataFile;

    public WikiService(FileStorageService fileStorageService, MdConvertService convertService,
            ObjectMapper mapper) {
        this.fileStorageService = fileStorageService;
        this.convertService = convertService;
        this.mapper = mapper;
    }

    /** 文档列表（先与文件元数据对账并清理孤儿，再逐篇抽取/合并元信息） */
    public synchronized WikiListView list() {
        List<FileEntry> entries = successEntries();
        Set<String> valid = Set.copyOf(entries.stream().map(FileEntry::mdName).toList());
        WikiMetadataFile mf = cleanup(loadMetadata(), valid);
        Map<String, WikiMetaEntry> manual = byMdName(mf);
        List<WikiDocView> docs = new ArrayList<>();
        for (FileEntry e : entries) {
            String content = readMd(e);
            docs.add(viewOf(e, content, manual.get(e.mdName())));
        }
        return new WikiListView(mf.version(), docs);
    }

    /** 单篇文档（MD 原文 + 合并元信息） */
    public synchronized WikiDocResult doc(String mdName) {
        FileEntry e = findSuccess(mdName, successEntries());
        WikiMetaEntry meta = byMdName(loadMetadata()).get(mdName);
        String content = readMd(e);
        String body = stripSourceLine(content);
        return new WikiDocResult(e.mdName(), e.name(), extractTitle(body, e.mdName()),
                extract(P_DATE, body), extract(P_SOURCE, body), extract(P_AUTHOR, body),
                meta == null ? null : meta.category(), meta == null ? null : meta.tags(),
                e.convertedAt(), content);
    }

    /** 编辑元信息（category 置空、tags 置空即清除；孤儿清理后 upsert，version +1） */
    public synchronized WikiMetaUpdateResult updateMeta(String mdName, String category, List<String> tags) {
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
        return new WikiMetaUpdateResult(next.version(),
                viewOf(e, readMd(e), updated));
    }

    // ---------------- 内部 ----------------

    /** SUCCESS 条目（保持文件列表顺序）；顺带触发文件对账与 MD 转换补齐 */
    private List<FileEntry> successEntries() {
        FileListView fv = fileStorageService.list();
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

    private WikiDocView viewOf(FileEntry e, String content, WikiMetaEntry meta) {
        String body = stripSourceLine(content);
        return new WikiDocView(e.mdName(), e.name(), extractTitle(body, e.mdName()),
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
