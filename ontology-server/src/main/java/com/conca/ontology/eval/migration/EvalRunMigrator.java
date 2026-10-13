package com.conca.ontology.eval.migration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.conca.ontology.eval.entity.EvalRunEntity;
import com.conca.ontology.eval.entity.EvalRunSampleEntity;
import com.conca.ontology.eval.mapper.EvalRunMapper;
import com.conca.ontology.eval.mapper.EvalRunSampleMapper;
import com.conca.ontology.shared.db.migration.JsonMigrator;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * data/eval/results/eval-*.json（每任务一文件）→ eval_run + eval_run_sample 存量迁移（P6 评测二）。
 * 每任务一行 + 每样本一行（自增 id 兼作 samples 数组原序）；三代文件 schema（老/中/新）由
 * 实体 of() 转换器统一泛化解析，缺失键按 NULL / 空数组处理；
 * 老格式 samples[].expected 为死字段（全代码零消费、前端零引用）：直接丢弃不落库。
 * 源为目录（对齐 UserPromptMigrator 先例）：importAll 按文件名序逐个导入，
 * 成功后整个目录由 Runner 归档为 results.migrated（保留回退现场）。
 * 全新部署无默认数据：表空 = 无历史任务，对齐原「目录不存在视为空列表」（initDefaults 空实现）。
 */
@Component
@Order(80)
public class EvalRunMigrator implements JsonMigrator {

    private static final Logger log = LoggerFactory.getLogger(EvalRunMigrator.class);

    /** 与 EvalRunService RUN_ID_RE 同构：eval-时间戳-随机码.json，迁移器独立持有（服务侧已无文件名语义） */
    private static final Pattern RUN_FILE_RE = Pattern.compile("^eval-\\d{8}-\\d{6}-[0-9a-f]{1,3}\\.json$");

    private final ObjectMapper mapper;
    private final EvalRunMapper runMapper;
    private final EvalRunSampleMapper sampleMapper;
    private final String resultDir;

    public EvalRunMigrator(ObjectMapper mapper, EvalRunMapper runMapper, EvalRunSampleMapper sampleMapper,
            @Value("${app.eval.result-dir:data/eval/results}") String resultDir) {
        this.mapper = mapper;
        this.runMapper = runMapper;
        this.sampleMapper = sampleMapper;
        this.resultDir = resultDir;
    }

    @Override
    public String name() {
        return "eval-run";
    }

    /** 源为目录（每任务一文件）；Runner 以存在性判断，归档时整体 rename */
    @Override
    public Path sourceFile() {
        return Path.of(resultDir).toAbsolutePath().normalize();
    }

    /** 目录存在且含至少一个结果文件才视为有存量（纯 .tmp 残留不触发迁移与残留告警） */
    @Override
    public boolean sourcePresent() {
        return !resultFiles().isEmpty();
    }

    @Override
    public boolean targetTableEmpty() {
        return runMapper.selectCount(null) == 0;
    }

    @Override
    @Transactional
    public void importAll() throws Exception {
        List<Path> files = resultFiles();
        int samples = 0;
        for (Path p : files) {
            @SuppressWarnings("unchecked")
            Map<String, Object> run = mapper.readValue(p.toFile(), Map.class);
            String runId = p.getFileName().toString().replaceFirst("\\.json$", "");
            runMapper.insert(EvalRunEntity.of(run));
            if (run.get("samples") instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> row) {
                        sampleMapper.insert(EvalRunSampleEntity.of(runId, (Map<String, Object>) row));
                        samples++;
                    }
                }
            }
        }
        log.info("eval 结果目录导入完成：{} 个任务 / {} 个样本行", files.size(), samples);
    }

    private List<Path> resultFiles() {
        Path dir = sourceFile();
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> RUN_FILE_RE.matcher(p.getFileName().toString()).matches())
                    .sorted()
                    .toList();
        } catch (Exception e) {
            log.warn("扫描评测结果目录失败: {}", e.getMessage());
            return List.of();
        }
    }
}
