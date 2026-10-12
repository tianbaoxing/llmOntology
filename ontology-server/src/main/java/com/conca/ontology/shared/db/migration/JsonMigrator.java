package com.conca.ontology.shared.db.migration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * JSON 存量迁移器：把遗留 JSON 文件数据导入 MySQL 目标表。
 * 各存储域（用户 / 图谱配置 / 规则等）按期各提供一个实现并挂载为 Spring Bean，
 * 由 {@link JsonMigrationRunner} 在启动末尾统一调度，后续分期迁移零骨架改动。
 */
public interface JsonMigrator {

    /** 迁移项名称（日志标识） */
    String name();

    /** 源 JSON 文件路径（遗留数据位置，归档后不存在） */
    Path sourceFile();

    /** 目标表是否为空（非空视为已迁移） */
    boolean targetTableEmpty();

    /** 从源 JSON 全量导入目标表（仅在表空且文件存在时被调用，需保证原子性） */
    void importAll() throws Exception;

    /** 全新部署默认初始化（表空且无源 JSON时被调用；无默认数据的需求空实现即可） */
    default void initDefaults() {
    }

    /**
     * 源是否仍有待迁移数据（表空时分流 importAll/initDefaults，表非空时残留告警）。
     * 默认 sourceFile 存在性；混合目录源（如 data/agents：agent.json 需迁移而
     * workspace/state 长期存在）覆盖为「是否存在至少一份待迁移文件」，避免目录常驻导致的误判。
     */
    default boolean sourcePresent() {
        return Files.exists(sourceFile());
    }

    /**
     * importAll 成功且事务提交后由 Runner 调用的源归档。
     * 默认整体 rename 为 *.migrated（保留回退现场）；混合目录源可覆盖为逐文件精细归档，
     * 保留目录骨架与其余子目录（如 data/agents/{id}/ 只归档 agent.json，workspace/state 不动）。
     */
    default void archive() throws IOException {
        Path src = sourceFile();
        Files.move(src, src.resolveSibling(src.getFileName() + ".migrated"), StandardCopyOption.REPLACE_EXISTING);
    }
}
