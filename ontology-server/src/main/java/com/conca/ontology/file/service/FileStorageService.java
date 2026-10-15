package com.conca.ontology.file.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.ZoneId;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.conca.ontology.file.converter.FileEntryConverter;
import com.conca.ontology.file.dto.result.FileDeleteResult;
import com.conca.ontology.file.dto.view.FileListView;
import com.conca.ontology.file.dto.result.FileUpdateResult;
import com.conca.ontology.file.dto.result.FileUploadResult;
import com.conca.ontology.file.entity.FileMetadataRow;
import com.conca.ontology.file.mapper.FileMetadataMapper;
import com.conca.ontology.file.model.FileEntry;
import com.conca.ontology.shared.web.ApiException;

/**
 * 上传文件管理：文件实体存 app.upload-dir（默认 files/），元数据存 file_metadata 表（每文件一行，P4）。
 * 列表查询前先 reconcile（目录新文件补录、已消失文件剔除），保证库与磁盘一致；
 * 行级 CRUD + 同名覆盖保留原位置（自增 id 兼作列表顺序，无全局 version）。
 * 二进制文件（pdf/word/excel）同步转 Markdown 存 files-md/（MdConvertService）：
 * 上传即转、对账补转（存量/被删 MD 自动重建，FAILED 不自动重试）、删除连带删 MD。
 */
@Service
public class FileStorageService {

    private static final Logger log = LoggerFactory.getLogger(FileStorageService.class);

    private final MdConvertService convertService;
    private final FileMetadataMapper fileMapper;
    private final TransactionTemplate txTemplate;

    @Value("${app.upload-dir:files}")
    private String uploadDir;

    @Value("${app.upload-allowed-ext:pdf,txt,csv,md,json,xml,doc,docx,xls,xlsx}")
    private String allowedExt;

    public FileStorageService(MdConvertService convertService, FileMetadataMapper fileMapper,
            TransactionTemplate txTemplate) {
        this.convertService = convertService;
        this.fileMapper = fileMapper;
        this.txTemplate = txTemplate;
    }

    /** 全量列表（先与磁盘对账、补齐 MD 转换再返回；供 admin 页与 LLM 共用） */
    public synchronized FileListView list() {
        List<FileEntry> files = ensureConverted(reconcile()).stream()
                .map(FileEntryConverter::toModel).toList();
        return new FileListView(files);
    }

    /** 上传：白名单校验 → 原子落盘（同名覆盖）→ 元数据 upsert → 同步转 MD（失败不阻塞上传） */
    public synchronized FileUploadResult upload(MultipartFile file, String description) {
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
            FileMetadataRow row = new FileMetadataRow();
            row.setName(name);
            row.setPath(target.toAbsolutePath().toString());
            row.setSize(Files.size(target));
            row.setExt(ext);
            row.setDescription(cleanDesc(description));
            row.setUploadedAt(LocalDateTime.now());
            // 先对账（补录历史文件），再 upsert 本条，最后统一补转换（新文件在此转，避免重复转换）
            reconcile();
            upsert(row);
            FileMetadataRow finalRow = requireRow(ensureConverted(loadRows()), name);
            FileEntry finalEntry = FileEntryConverter.toModel(finalRow);
            log.info("file uploaded: {} ({} bytes, convert: {})", name, finalEntry.size(), finalEntry.convertStatus());
            return new FileUploadResult(finalEntry);
        } catch (ApiException e) {
            throw e;
        } catch (IOException e) {
            throw new ApiException(500, "保存文件失败: " + e.getMessage());
        }
    }

    /** 编辑文件说明（保留转换字段） */
    public synchronized FileUpdateResult updateDescription(String name, String description) {
        FileMetadataRow row = requireRow(reconcile(), name);
        row.setDescription(cleanDesc(description));
        fileMapper.updateById(row);
        return new FileUpdateResult(FileEntryConverter.toModel(row));
    }

    /** 删除：文件实体 + 对应 MD + 元数据记录一并移除 */
    public synchronized FileDeleteResult delete(String name) {
        FileMetadataRow row = requireRow(reconcile(), name);
        try {
            Path dir = uploadPath();
            Path target = dir.resolve(name).normalize();
            if (!target.startsWith(dir)) {
                throw new ApiException(400, "非法文件名: " + name);
            }
            Files.deleteIfExists(target);
            // 连带删除转换产物 MD（不存在则忽略）
            Files.deleteIfExists(convertService.mdPathOf(convertService.mdNameOf(name)));
        } catch (IOException e) {
            throw new ApiException(500, "删除文件失败: " + e.getMessage());
        }
        fileMapper.deleteById(row.getId());
        log.info("file deleted: {}", name);
        return new FileDeleteResult(row.getName());
    }

    /** 手动重试转换（FAILED 修复后使用；强制重转，先删旧 MD） */
    public synchronized FileUpdateResult reconvert(String name) {
        FileMetadataRow row = requireRow(reconcile(), name);
        if (!convertService.shouldConvert(row.getExt())) {
            throw new ApiException(400, "该类型无需转换: " + row.getExt());
        }
        convertRow(row, true);
        return new FileUpdateResult(FileEntryConverter.toModel(row));
    }

    /** MD 绝对路径（LLM 视图用；入参 mdName） */
    public String mdPathOf(String mdName) {
        return convertService.mdPathOf(mdName).toAbsolutePath().toString();
    }

    // ---------------- 内部 ----------------

    /** 目录与元数据对账：磁盘新文件补录（说明留空，时间取 mtime），已消失文件剔除；变更原子提交 */
    private List<FileMetadataRow> reconcile() {
        Path dir = uploadPath();
        Map<String, FileMetadataRow> byName = new LinkedHashMap<>();
        for (FileMetadataRow row : loadRows()) {
            byName.put(row.getName(), row);
        }
        List<FileMetadataRow> added = new ArrayList<>();
        List<Long> removedIds = new ArrayList<>();
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
                        FileMetadataRow row = new FileMetadataRow();
                        row.setName(rel);
                        row.setPath(p.toAbsolutePath().toString());
                        row.setSize(Files.size(p));
                        row.setExt(extOf(rel));
                        row.setDescription("");
                        row.setUploadedAt(LocalDateTime.ofInstant(
                                Files.getLastModifiedTime(p).toInstant(), ZoneId.systemDefault()));
                        byName.put(rel, row);
                        added.add(row);
                    } catch (IOException ignore) {
                        // 读不到大小/时间的文件跳过
                    }
                }
            }
            // 元数据里有、磁盘上没有的 → 剔除
            for (FileMetadataRow row : byName.values().stream()
                    .filter(r -> !onDisk.contains(r.getName())).toList()) {
                byName.remove(row.getName());
                removedIds.add(row.getId());
            }
        } catch (IOException e) {
            throw new ApiException(500, "扫描文件目录失败: " + e.getMessage());
        }
        if (!added.isEmpty() || !removedIds.isEmpty()) {
            txTemplate.executeWithoutResult(tx -> {
                added.forEach(fileMapper::insert);
                removedIds.forEach(fileMapper::deleteById);
            });
        }
        return new ArrayList<>(byName.values());
    }

    /**
     * MD 转换对账（reconcile 之后统一执行，逐行落库）：
     * - 文本类 → 回填 NONE；二进制缺 MD 且非 FAILED → 转换（存量历史文件/被删 MD 自动补）；
     * - MD 已存在但状态缺失 → 直接回填 SUCCESS（避免重复转换）；FAILED 不自动重试（reconvert 手动触发）。
     */
    private List<FileMetadataRow> ensureConverted(List<FileMetadataRow> rows) {
        for (FileMetadataRow row : rows) {
            if (!convertService.shouldConvert(row.getExt())) {
                if (!FileEntry.STATUS_NONE.equals(row.getConvertStatus())) {
                    applyConvert(row, null, FileEntry.STATUS_NONE, null, null);
                    fileMapper.updateById(row);
                }
            } else {
                convertRow(row, false);
            }
        }
        return rows;
    }

    /** 单行转换：force=true 强制重转（先删旧 MD）；force=false 仅在缺 MD 且非 FAILED 时转换 */
    private void convertRow(FileMetadataRow row, boolean force) {
        String mdName = convertService.mdNameOf(row.getName());
        Path mdPath = convertService.mdPathOf(mdName);
        boolean mdExists = Files.exists(mdPath);
        if (!force && (mdExists || FileEntry.STATUS_FAILED.equals(row.getConvertStatus()))) {
            // MD 在而状态缺失（旧数据/中间态）→ 回填；FAILED 且无 MD → 不动
            if (mdExists && !FileEntry.STATUS_SUCCESS.equals(row.getConvertStatus())) {
                applyConvert(row, mdName, FileEntry.STATUS_SUCCESS, null,
                        FileEntryConverter.parseTime(convertService.mtimeOf(mdPath)));
                fileMapper.updateById(row);
            }
            return;
        }
        MdConvertService.ConvertResult r = convertService.convertAndWrite(
                Path.of(row.getPath()), row.getName());
        applyConvert(row, mdName, r.status(), r.msg(),
                FileEntry.STATUS_SUCCESS.equals(r.status()) ? LocalDateTime.now() : null);
        fileMapper.updateById(row);
    }

    private static void applyConvert(FileMetadataRow row, String mdName, String status, String msg,
            LocalDateTime convertedAt) {
        row.setMdName(mdName);
        row.setConvertStatus(status);
        row.setConvertMsg(msg);
        row.setConvertedAt(convertedAt);
    }

    /** upsert：同 name 覆盖保留原位置（不动 id），新 name 追加尾部（自增） */
    private void upsert(FileMetadataRow row) {
        FileMetadataRow existing = selectByName(row.getName());
        if (existing != null) {
            row.setId(existing.getId());
            fileMapper.updateById(row);
        } else {
            fileMapper.insert(row);
        }
    }

    private FileMetadataRow selectByName(String name) {
        return fileMapper.selectOne(Wrappers.<FileMetadataRow>lambdaQuery()
                .eq(FileMetadataRow::getName, name));
    }

    /** 全量行（按 id 序 = 列表顺序） */
    private List<FileMetadataRow> loadRows() {
        return fileMapper.selectList(Wrappers.<FileMetadataRow>lambdaQuery()
                .orderByAsc(FileMetadataRow::getId));
    }

    private FileMetadataRow requireRow(List<FileMetadataRow> rows, String name) {
        return rows.stream().filter(r -> r.getName().equals(name)).findFirst()
                .orElseThrow(() -> new ApiException(404, "文件不存在: " + name));
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

    private Path uploadPath() {
        return Path.of(uploadDir).toAbsolutePath().normalize();
    }
}
