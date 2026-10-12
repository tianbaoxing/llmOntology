package com.conca.ontology.shared.db.migration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * JSON 存量迁移骨架：启动末尾（Flyway 建表之后）逐项调度所有 {@link JsonMigrator}——
 * 表空 + 源存在 → 导入并归档（rename .migrated，保留回退现场）；
 * 表空 + 源不存在 → 执行全新部署默认初始化；
 * 表非空 → 跳过（幂等），源仍在时告警提示人工确认。
 * 源路径可为文件或目录（P5 起支持目录型源，如 data/prompts 按用户一文件；归档时整体 rename）。
 * 任一项失败抛异常阻断启动，防止半迁移状态下业务读到空表。
 */
@Component
public class JsonMigrationRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(JsonMigrationRunner.class);

    private final List<JsonMigrator> migrators;
    private final boolean enabled;

    public JsonMigrationRunner(List<JsonMigrator> migrators,
            @Value("${app.migration.json-import-enabled:true}") boolean enabled) {
        this.migrators = migrators;
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.info("JSON 存量迁移开关关闭（app.migration.json-import-enabled=false），跳过");
            return;
        }
        migrators.forEach(this::runOne);
    }

    private void runOne(JsonMigrator m) {
        if (!m.targetTableEmpty()) {
            if (Files.exists(m.sourceFile())) {
                log.warn("[{}] 目标表已有数据，保留源 JSON 不导入（如需重导请先清表）：{}", m.name(), m.sourceFile());
            } else {
                log.debug("[{}] 目标表已有数据，无需迁移", m.name());
            }
            return;
        }
        if (!Files.exists(m.sourceFile())) {
            m.initDefaults();
            log.info("[{}] 全新部署，已执行默认初始化", m.name());
            return;
        }
        try {
            m.importAll();
        } catch (Exception e) {
            throw new IllegalStateException("[" + m.name() + "] JSON 存量迁移失败：" + e.getMessage(), e);
        }
        archive(m);
    }

    private void archive(JsonMigrator m) {
        Path src = m.sourceFile();
        Path dst = src.resolveSibling(src.getFileName() + ".migrated");
        try {
            Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("[" + m.name() + "] 迁移完成但源 JSON 归档失败：" + dst, e);
        }
        log.info("[{}] 存量迁移完成，源 JSON 已归档为 {}", m.name(), dst.getFileName());
    }
}
