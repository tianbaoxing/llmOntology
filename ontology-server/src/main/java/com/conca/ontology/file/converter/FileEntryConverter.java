package com.conca.ontology.file.converter;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import com.conca.ontology.file.entity.FileMetadataRow;
import com.conca.ontology.file.model.FileEntry;

/**
 * {@link FileMetadataRow} 行 ↔ {@link FileEntry} 模型转换器（P4）。
 * FileStorageService 持久化用，保证行映射唯一实现；
 * 时间列 DATETIME ↔ API 的 yyyy-MM-dd HH:mm:ss 字符串（前端零感知）。
 *
 * @author tianbaoxing
 * @date 2026-10-10
 */
public final class FileEntryConverter {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private FileEntryConverter() {
    }

    /** 模型 → 新行（不设 id：insert 自增追加尾部；覆盖场景由调用方回填原 id） */
    public static FileMetadataRow toRow(FileEntry e) {
        FileMetadataRow row = new FileMetadataRow();
        row.setName(e.name());
        row.setPath(e.path());
        row.setSize(e.size());
        row.setExt(e.ext());
        row.setDescription(e.description());
        row.setUploadedAt(parseTime(e.uploadedAt()));
        row.setMdName(e.mdName());
        row.setConvertStatus(e.convertStatus());
        row.setConvertMsg(e.convertMsg());
        row.setConvertedAt(parseTime(e.convertedAt()));
        return row;
    }

    public static FileEntry toModel(FileMetadataRow row) {
        return new FileEntry(row.getName(), row.getPath(), row.getSize(), row.getExt(),
                row.getDescription(), formatTime(row.getUploadedAt()),
                row.getMdName(), row.getConvertStatus(), row.getConvertMsg(),
                formatTime(row.getConvertedAt()));
    }

    public static LocalDateTime parseTime(String s) {
        return s == null ? null : LocalDateTime.parse(s, TIME_FMT);
    }

    public static String formatTime(LocalDateTime t) {
        return t == null ? null : t.format(TIME_FMT);
    }
}
