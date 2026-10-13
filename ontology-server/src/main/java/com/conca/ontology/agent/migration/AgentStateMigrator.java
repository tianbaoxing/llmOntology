package com.conca.ontology.agent.migration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.conca.ontology.shared.db.MySqlAgentStateStores;
import com.conca.ontology.shared.db.migration.JsonMigrator;

/**
 * 框架会话状态存量迁移（P8）：JsonFileAgentStateStore 的 5 类 state 目录 → 官方
 * MysqlAgentStateStore 每智能体一张 agent_state_* 表。
 * 源布局 {stateDir}/{user}/{session}/{key}.json（或 .jsonl 列表逐行）与官方文件实现同构：
 * 目录段按官方 safeSegment 规则 Base64 URL 编码，迁移时反向 decode 还原原始 userId/sessionId，
 * slotId 拼接为 {userId}:{sessionId}（与运行时官方 MySQL 实现逐字节一致）；
 * state_data 存文件内容原样字符串（官方 get() 按调用方传入的 Class 反序列化，不校验存储格式，
 * pretty/compact 混存无害）。.hash 增量写优化文件丢弃（getList 读路径不依赖，首次增量写自动全量重写）。
 * 目标表由官方构造器幂等建（createIfNotExist=true），DDL 先于全部行插入执行，
 * MySQL DDL 隐式提交不影响后续 INSERT 的事务原子性；行插入 INSERT IGNORE（启动窗口新会话先行入库时跳过，DB 为准）。
 * targetTableEmpty 为多表聚合语义：任一目标表不存在或零行即视为待迁移（表非空+源在的残留告警语义由 Runner 统一承担）。
 */
@Component
@Order(100)
public class AgentStateMigrator implements JsonMigrator {

    private static final Logger log = LoggerFactory.getLogger(AgentStateMigrator.class);

    /** 与官方 JsonFileAgentStateStore.SAFE_FILENAME_PATTERN 同构（目录段转义判定） */
    private static final Pattern SAFE_SEGMENT = Pattern.compile("^[a-zA-Z0-9_\\-.]+$");

    private static final String INSERT_ROW = "INSERT IGNORE INTO `" + MySqlAgentStateStores.DATABASE_NAME
            + "`.`%s` (session_id, state_key, item_index, state_data) VALUES (?, ?, ?, ?)";

    /** 待迁移源：namespace（=表名后缀）+ state 目录 */
    private record Source(String namespace, Path dir) {
    }

    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private final Path mainDir;
    private final Path rewriteDir;
    private final Path judgeDir;
    private final Path agenticDir;
    private final Path agentsDir;

    public AgentStateMigrator(DataSource dataSource, JdbcTemplate jdbc,
            @Value("${app.agent.state-dir:data/agent-state}") String mainDir,
            @Value("${app.rewrite.state-dir:data/rewrite-state}") String rewriteDir,
            @Value("${app.eval.judge.state-dir:data/eval/judge-state}") String judgeDir,
            @Value("${app.eval.agent.state-dir:data/eval/agentic-judge-state}") String agenticDir,
            @Value("${app.agents.dir:data/agents}") String agentsDir) {
        this.dataSource = dataSource;
        this.jdbc = jdbc;
        this.mainDir = Path.of(mainDir).toAbsolutePath().normalize();
        this.rewriteDir = Path.of(rewriteDir).toAbsolutePath().normalize();
        this.judgeDir = Path.of(judgeDir).toAbsolutePath().normalize();
        this.agenticDir = Path.of(agenticDir).toAbsolutePath().normalize();
        this.agentsDir = Path.of(agentsDir).toAbsolutePath().normalize();
    }

    @Override
    public String name() {
        return "agent-state";
    }

    /** 主源目录（日志代表；实际按多源聚合判定） */
    @Override
    public Path sourceFile() {
        return mainDir;
    }

    /** 任一源目录存在待迁移 state 文件（.json/.jsonl；.hash 不算） */
    @Override
    public boolean sourcePresent() {
        return !pendingSources().isEmpty();
    }

    /** 多表聚合：任一目标表（4 固定 + 自建）不存在或零行 → 视为有待迁移目标 */
    @Override
    public boolean targetTableEmpty() {
        for (Source s : allSources()) {
            if (tableEmpty(s.namespace())) {
                return true;
            }
        }
        return false;
    }

    @Override
    @Transactional
    public void importAll() throws Exception {
        List<Source> sources = pendingSources();
        // 先统一建表（官方 DDL 幂等；DDL 隐式提交发生在任何行插入之前）
        for (Source s : sources) {
            MySqlAgentStateStores.create(dataSource, s.namespace());
        }
        int rows = 0;
        for (Source s : sources) {
            rows += importDir(s);
        }
        log.info("会话状态目录导入完成：{} 个源 / {} 行（含 .jsonl 逐行展开）", sources.size(), rows);
    }

    /** 固定 4 源整体 rename *.migrated（P6b 先例）；自建智能体逐 {id}/state 归档保留目录骨架（P5b 先例） */
    @Override
    public void archive() throws IOException {
        for (Path dir : List.of(mainDir, rewriteDir, judgeDir, agenticDir)) {
            if (Files.isDirectory(dir)) {
                Files.move(dir, dir.resolveSibling(dir.getFileName() + ".migrated"),
                        StandardCopyOption.REPLACE_EXISTING);
            }
        }
        if (Files.isDirectory(agentsDir)) {
            try (Stream<Path> ids = Files.list(agentsDir)) {
                for (Path state : ids.filter(Files::isDirectory)
                        .map(id -> id.resolve("state"))
                        .filter(Files::isDirectory)
                        .toList()) {
                    Files.move(state, state.resolveSibling("state.migrated"),
                            StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    // ---------------- 内部 ----------------

    /** 固定 4 源 + 自建智能体源（目录存在即列，含空目录——建表/归档需要覆盖；导入过滤有文件的） */
    private List<Source> allSources() {
        List<Source> result = new ArrayList<>();
        result.add(new Source(MySqlAgentStateStores.MAIN, mainDir));
        result.add(new Source(MySqlAgentStateStores.REWRITE, rewriteDir));
        result.add(new Source(MySqlAgentStateStores.JUDGE, judgeDir));
        result.add(new Source(MySqlAgentStateStores.AGENTIC_JUDGE, agenticDir));
        result.addAll(customSources());
        return result;
    }

    /** 仅含待迁移文件的源（驱动 importAll） */
    private List<Source> pendingSources() {
        return allSources().stream().filter(s -> Files.isDirectory(s.dir()) && hasStateFiles(s.dir())).toList();
    }

    private List<Source> customSources() {
        if (!Files.isDirectory(agentsDir)) {
            return List.of();
        }
        try (Stream<Path> ids = Files.list(agentsDir)) {
            return ids.filter(Files::isDirectory)
                    .map(id -> new Source(id.getFileName().toString(), id.resolve("state")))
                    .filter(s -> Files.isDirectory(s.dir()))
                    .toList();
        } catch (IOException e) {
            log.warn("扫描自建智能体 state 目录失败：{}", e.getMessage());
            return List.of();
        }
    }

    /** 目录内（3 层深度内）存在 .json/.jsonl 即有待迁移数据 */
    private boolean hasStateFiles(Path dir) {
        try (Stream<Path> walk = Files.walk(dir, 3)) {
            return walk.filter(Files::isRegularFile)
                    .anyMatch(p -> {
                        String n = p.getFileName().toString();
                        return n.endsWith(".json") || n.endsWith(".jsonl");
                    });
        } catch (IOException e) {
            log.warn("扫描会话状态目录失败（{}）：{}", dir, e.getMessage());
            return false;
        }
    }

    /** 表不存在 = 空；存在则 COUNT(*) == 0 */
    private boolean tableEmpty(String namespace) {
        String table = MySqlAgentStateStores.tableNameOf(namespace);
        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = ? AND table_name = ?",
                Integer.class, MySqlAgentStateStores.DATABASE_NAME, table);
        if (exists == null || exists == 0) {
            return true;
        }
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM `" + MySqlAgentStateStores.DATABASE_NAME + "`.`" + table + "`",
                Integer.class);
        return count != null && count == 0;
    }

    /** 单源目录导入：{user}/{session}/{key}.json → 行(item_index=0)；.jsonl → 逐行 item_index 递增 */
    private int importDir(Source s) throws IOException {
        String sql = String.format(INSERT_ROW, MySqlAgentStateStores.tableNameOf(s.namespace()));
        int rows = 0;
        try (Stream<Path> walk = Files.walk(s.dir(), 3)) {
            for (Path f : walk.filter(Files::isRegularFile).sorted().toList()) {
                String name = f.getFileName().toString();
                boolean isList = name.endsWith(".jsonl");
                if (!isList && !name.endsWith(".json")) {
                    continue; // .hash 等非 state 数据
                }
                Path rel = s.dir().relativize(f);
                if (rel.getNameCount() != 3) {
                    log.warn("跳过非标准布局文件（期望 {user}/{session}/{key}）：{}", f);
                    continue;
                }
                String slotId = decodeSegment(rel.getName(0).toString()) + ":" + decodeSegment(rel.getName(1).toString());
                String key = isList ? name.substring(0, name.length() - 6) : name.substring(0, name.length() - 5);
                if (isList) {
                    int idx = 0;
                    for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                        if (line.isBlank()) {
                            continue;
                        }
                        jdbc.update(sql, slotId, key, idx++, line);
                        rows++;
                    }
                } else {
                    jdbc.update(sql, slotId, key, 0, Files.readString(f, StandardCharsets.UTF_8));
                    rows++;
                }
            }
        }
        return rows;
    }

    /** 与官方 JsonFileAgentStateStore.decodeSegment 同构：安全段原样，否则 Base64 URL 解码，失败原样返回 */
    private static String decodeSegment(String segment) {
        if (SAFE_SEGMENT.matcher(segment).matches()) {
            return segment;
        }
        try {
            return new String(Base64.getUrlDecoder().decode(segment), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return segment;
        }
    }
}
