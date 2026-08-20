package com.conca.ontology.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.conca.ontology.exception.ApiException;
import com.conca.ontology.model.FileEntry;
import com.conca.ontology.model.FileMetadataFile;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 上传文件管理：文件实体存 app.upload-dir（默认 files/），元数据存 data/file-metadata.json（唯一事实源）。
 * 列表查询前先 reconcile（目录新文件补录、已消失文件剔除），保证 JSON 与磁盘一致；
 * 每次元数据变更 version + 1 并原子落盘（tmp + rename）。
 */
@Service
public class FileStorageService {

    private static final Logger log = LoggerFactory.getLogger(FileStorageService.class);
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ObjectMapper mapper;

    @Value("${app.upload-dir:files}")
    private String uploadDir;

    @Value("${app.upload-allowed-ext:pdf,txt,csv,md,json,xml,doc,docx,xls,xlsx}")
    private String allowedExt;

    @Value("${app.metadata-file:data/file-metadata.json}")
    private String metadataFile;

    public FileStorageService(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** 全量列表（先与目录对账再返回；供 admin 页与 LLM 共用） */
    public synchronized Map<String, Object> list() {
        FileMetadataFile mf = reconcile();
        return Map.of("version", mf.version(), "files", mf.files());
    }

    /** 上传：白名单校验 → 原子落盘（同名覆盖）→ 元数据 upsert */
    public synchronized Map<String, Object> upload(MultipartFile file, String description) {
        String original = file.getOriginalFilename();
        if (original == null || original.isBlank()) {
            throw new ApiException(400, "文件名为空");
        }
        // 剥离客户端可能携带的路径部分，上传文件一律落根目录平铺
        String name = Path.of(original.replace('\\', '/')).getFileName().toString().trim();
        if (name.isEmpty() || name.contains("/") || name.contains("\\")) {
            throw new ApiException(400, "非法文件名: " + original);
        }
        String ext = extOf(name);
        if (!allowedExts().contains(ext)) {
            throw new ApiException(400, "不支持的文件类型: " + ext + "（允许: " + allowedExt + "）");
        }
        try {
            Path dir = uploadPath();
            Files.createDirectories(dir);
            Path target = dir.resolve(name).normalize();
            if (!target.startsWith(dir)) {
                throw new ApiException(400, "非法文件名: " + name);
            }
            Path tmp = dir.resolve(name + ".uploading.tmp");
            try {
                file.transferTo(tmp.toFile());
                // 同目录 move（同盘 rename），同名覆盖
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(tmp);
            }
            FileEntry entry = new FileEntry(name, target.toAbsolutePath().toString(),
                    Files.size(target), ext, cleanDesc(description), now());
            FileMetadataFile next = upsert(reconcile(), entry);
            persist(next);
            log.info("file uploaded: {} ({} bytes)", name, entry.size());
            return Map.of("version", next.version(), "file", entry);
        } catch (ApiException e) {
            throw e;
        } catch (IOException e) {
            throw new ApiException(500, "保存文件失败: " + e.getMessage());
        }
    }

    /** 编辑文件说明 */
    public synchronized Map<String, Object> updateDescription(String name, String description) {
        FileMetadataFile mf = reconcile();
        FileEntry found = find(mf, name);
        List<FileEntry> files = new ArrayList<>();
        for (FileEntry e : mf.files()) {
            files.add(e.name().equals(name)
                    ? new FileEntry(e.name(), e.path(), e.size(), e.ext(),
                            cleanDesc(description), e.uploadedAt())
                    : e);
        }
        FileMetadataFile next = new FileMetadataFile(mf.version() + 1, files);
        persist(next);
        return Map.of("version", next.version(), "file", find(next, name));
    }

    /** 删除：文件实体 + 元数据记录一并移除 */
    public synchronized Map<String, Object> delete(String name) {
        FileMetadataFile mf = reconcile();
        FileEntry found = find(mf, name);
        try {
            Path dir = uploadPath();
            Path target = dir.resolve(name).normalize();
            if (!target.startsWith(dir)) {
                throw new ApiException(400, "非法文件名: " + name);
            }
            Files.deleteIfExists(target);
        } catch (IOException e) {
            throw new ApiException(500, "删除文件失败: " + e.getMessage());
        }
        List<FileEntry> files = new ArrayList<>(mf.files());
        files.removeIf(e -> e.name().equals(name));
        FileMetadataFile next = new FileMetadataFile(mf.version() + 1, files);
        persist(next);
        log.info("file deleted: {}", name);
        return Map.of("version", next.version(), "deleted", found.name());
    }

    // ---------------- 内部 ----------------

    /** 目录与元数据对账：磁盘新文件补录（说明留空，时间取 mtime），已消失文件剔除 */
    private FileMetadataFile reconcile() {
        Path dir = uploadPath();
        Map<String, FileEntry> byName = new LinkedHashMap<>();
        FileMetadataFile mf = loadMetadata();
        for (FileEntry e : mf.files()) {
            byName.put(e.name(), e);
        }
        boolean changed = false;
        try {
            Files.createDirectories(dir);
            List<Path> diskFiles;
            try (Stream<Path> walk = Files.walk(dir)) {
                diskFiles = walk.filter(Files::isRegularFile).toList();
            }
            List<String> onDisk = new ArrayList<>();
            for (Path p : diskFiles) {
                // 相对名统一用 '/'，元数据 name 与 REST 路径入参一致
                String rel = dir.relativize(p).toString().replace('\\', '/');
                onDisk.add(rel);
                if (!byName.containsKey(rel)) {
                    try {
                        byName.put(rel, new FileEntry(rel, p.toAbsolutePath().toString(),
                                Files.size(p), extOf(rel), "",
                                LocalDateTime.ofInstant(
                                        Files.getLastModifiedTime(p).toInstant(), ZoneId.systemDefault())
                                        .format(TIME_FMT)));
                        changed = true;
                    } catch (IOException ignore) {
                        // 读不到大小/时间的文件跳过
                    }
                }
            }
            // 元数据里有、磁盘上没有的 → 剔除
            for (String n : byName.keySet().stream().filter(n -> !onDisk.contains(n)).toList()) {
                byName.remove(n);
                changed = true;
            }
        } catch (IOException e) {
            throw new ApiException(500, "扫描文件目录失败: " + e.getMessage());
        }
        FileMetadataFile result = new FileMetadataFile(mf.version(), new ArrayList<>(byName.values()));
        if (changed) {
            result = new FileMetadataFile(mf.version() + 1, result.files());
            persist(result);
        }
        return result;
    }

    private FileMetadataFile upsert(FileMetadataFile mf, FileEntry entry) {
        Map<String, FileEntry> byName = new LinkedHashMap<>();
        for (FileEntry e : mf.files()) {
            byName.put(e.name(), e);
        }
        byName.put(entry.name(), entry);
        return new FileMetadataFile(mf.version() + 1, new ArrayList<>(byName.values()));
    }

    private FileEntry find(FileMetadataFile mf, String name) {
        return mf.files().stream().filter(e -> e.name().equals(name)).findFirst()
                .orElseThrow(() -> new ApiException(404, "文件不存在: " + name));
    }

    private void persist(FileMetadataFile mf) {
        try {
            Path file = metadataPath();
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), mf);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            throw new IllegalStateException("failed to save file metadata: " + metadataFile, e);
        }
    }

    private FileMetadataFile loadMetadata() {
        Path file = metadataPath();
        if (Files.notExists(file)) {
            return new FileMetadataFile(0, List.of());
        }
        try {
            return mapper.readValue(file.toFile(), FileMetadataFile.class);
        } catch (Exception e) {
            throw new IllegalStateException("failed to load file metadata: " + file.toAbsolutePath(), e);
        }
    }

    private Set<String> allowedExts() {
        return Set.of(allowedExt.split(","));
    }

    private static String extOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** 说明清洗：去空白 + 剥离 UTF-8 BOM 前缀 */
    private static String cleanDesc(String s) {
        if (s == null) {
            return "";
        }
        s = s.strip();
        if (!s.isEmpty() && s.charAt(0) == '\uFEFF') {
            s = s.substring(1).trim();
        }
        return s;
    }

    private static String now() {
        return LocalDateTime.now().format(TIME_FMT);
    }

    private Path uploadPath() {
        return Path.of(uploadDir).toAbsolutePath().normalize();
    }

    private Path metadataPath() {
        return Path.of(metadataFile).toAbsolutePath();
    }
}
