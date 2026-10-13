package com.conca.ontology.eval.migration;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.conca.ontology.eval.entity.DatasetEntity;
import com.conca.ontology.eval.entity.DatasetSampleEntity;
import com.conca.ontology.eval.mapper.DatasetMapper;
import com.conca.ontology.eval.mapper.DatasetSampleMapper;
import com.conca.ontology.shared.db.migration.JsonMigrator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * data/eval/datasets/*.jsonl（每数据集一文件，每非空行一样本）→ dataset + dataset_sample 存量迁移（P6 评测三）。
 * 每数据集一行（name = 文件名）+ 每非空行一样本行（自增 id 兼作文件行序）；
 * 行解析走 DatasetSampleEntity.of（与原 loadSamples 同构：NULL 键保真、turns 严格校验、
 * 字符串清单空归 NULL），坏行抛异常阻断启动（框架约定，逼人工修复源文件后重导）。
 * 源为目录（对齐 EvalRunMigrator 先例）：按文件名序逐个导入，成功后整个目录由 Runner
 * 归档为 datasets.migrated（保留回退现场）。
 * 全新部署无默认数据：表空 = 无数据集，对齐原「目录不存在视为空列表」（initDefaults 空实现）。
 */
@Component
@Order(90)
public class EvalDatasetMigrator implements JsonMigrator {

    private static final Logger log = LoggerFactory.getLogger(EvalDatasetMigrator.class);

    private final ObjectMapper mapper;
    private final DatasetMapper datasetMapper;
    private final DatasetSampleMapper sampleMapper;
    private final String datasetDir;

    public EvalDatasetMigrator(ObjectMapper mapper, DatasetMapper datasetMapper, DatasetSampleMapper sampleMapper,
            @Value("${app.eval.dataset-dir:data/eval/datasets}") String datasetDir) {
        this.mapper = mapper;
        this.datasetMapper = datasetMapper;
        this.sampleMapper = sampleMapper;
        this.datasetDir = datasetDir;
    }

    @Override
    public String name() {
        return "eval-dataset";
    }

    /** 源为目录（每数据集一文件）；Runner 以存在性判断，归档时整体 rename */
    @Override
    public Path sourceFile() {
        return Path.of(datasetDir).toAbsolutePath().normalize();
    }

    /** 目录存在且含至少一个 .jsonl 文件才视为有存量（对齐原 listDatasets 扫描过滤口径） */
    @Override
    public boolean sourcePresent() {
        return !datasetFiles().isEmpty();
    }

    @Override
    public boolean targetTableEmpty() {
        return datasetMapper.selectCount(null) == 0;
    }

    @Override
    @Transactional
    public void importAll() throws Exception {
        List<Path> files = datasetFiles();
        int samples = 0;
        for (Path p : files) {
            String dsName = p.getFileName().toString();
            datasetMapper.insert(new DatasetEntity(dsName));
            try (var reader = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    JsonNode node = mapper.readTree(line);
                    sampleMapper.insert(DatasetSampleEntity.of(dsName, node, mapper));
                    samples++;
                }
            }
        }
        log.info("数据集目录导入完成：{} 个数据集 / {} 个样本行", files.size(), samples);
    }

    /** 与原 listDatasets 扫描同口径：常规 .jsonl 文件按文件名序（不做名称 RE 校验，列表页同样不过滤） */
    private List<Path> datasetFiles() {
        Path dir = sourceFile();
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                    .sorted()
                    .toList();
        } catch (Exception e) {
            log.warn("扫描数据集目录失败: {}", e.getMessage());
            return List.of();
        }
    }
}
