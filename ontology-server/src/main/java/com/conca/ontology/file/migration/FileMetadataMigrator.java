package com.conca.ontology.file.migration;

import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.conca.ontology.file.entity.FileMetadataRow;
import com.conca.ontology.file.mapper.FileMetadataMapper;
import com.conca.ontology.file.model.FileEntry;
import com.conca.ontology.file.model.FileEntryConverter;
import com.conca.ontology.shared.db.migration.JsonMigrator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * file-metadata.json → file_metadata 多行表存量迁移（P4）。
 * 每文件一行（保持原文件声明顺序），文件全局 version 不再落库。
 * 全新部署无默认数据：表空 = 空目录，FileStorageService 对账时自动补录磁盘文件。
 */
@Component
@Order(50)
public class FileMetadataMigrator implements JsonMigrator {

    private static final Logger log = LoggerFactory.getLogger(FileMetadataMigrator.class);

    private final ObjectMapper mapper;
    private final FileMetadataMapper fileMapper;
    private final String metadataFile;

    public FileMetadataMigrator(ObjectMapper mapper, FileMetadataMapper fileMapper,
            @Value("${app.metadata-file:data/file-metadata.json}") String metadataFile) {
        this.mapper = mapper;
        this.fileMapper = fileMapper;
        this.metadataFile = metadataFile;
    }

    @Override
    public String name() {
        return "file-metadata";
    }

    @Override
    public Path sourceFile() {
        return Path.of(metadataFile).toAbsolutePath().normalize();
    }

    @Override
    public boolean targetTableEmpty() {
        return fileMapper.selectCount(null) == 0;
    }

    @Override
    @Transactional
    public void importAll() throws Exception {
        JsonNode files = mapper.readTree(Files.readString(sourceFile())).path("files");
        int count = 0;
        for (JsonNode f : files) {
            FileEntry entry = mapper.treeToValue(f, FileEntry.class);
            fileMapper.insert(FileEntryConverter.toRow(entry));
            count++;
        }
        log.info("file-metadata.json 导入完成：{} 个文件", count);
    }
}
