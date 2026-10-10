package com.conca.ontology.shared.db.migration;

import java.nio.file.Path;

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
}
