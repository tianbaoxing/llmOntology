package com.conca.ontology.service;

import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.conca.ontology.shared.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 备份：图数据库全量（nodes/rels JSONL，eid 关联两端）+ data 三个 JSON + 上传文件目录，打包为单个 zip。
 * zip 文件名编码统计信息（backup-时间-n节点数-r关系数-f文件数-v版本.zip），列表即文件名解析，无需额外索引。
 * 异步执行 + 内存进度（percent/phase/detail），同一时间仅允许一个任务。
 * 临时工作目录 backups/.tmp-{taskId}/，完成后 zip 先写 .tmp 再 rename（半成品不进列表）。
 */
@Service
public class BackupService {

    private static final Logger log = LoggerFactory.getLogger(BackupService.class);

    /** 合法备份文件名：backup-20260820-153045-n6036-r21422-f1-v8.zip */
    private static final Pattern NAME_RE = Pattern.compile(
            "^backup-(\\d{8}-\\d{6})-n(\\d+)-r(\\d+)-f(\\d+)-v(\\d+)\\.zip$");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final int BATCH = 1000;

    /** 进度任务（内存态，服务重启即失，备份产物本身已落盘不受影响） */
    public static final class Task {
        public final String id;
        volatile String status = "running";   // running | success | failed
        volatile String phase = "init";       // init | nodes | rels | files | zip | done
        volatile String detail = "准备中";
        volatile int percent = 0;
        volatile String error;
        volatile String backupName;

        Task(String id) {
            this.id = id;
        }
    }

    private final GraphQueryService queryService;
    private final SchemaService schemaService;
    private final ObjectMapper mapper;

    @Value("${app.backup-dir:backups}")
    private String backupDir;

    @Value("${graph.schema-file:data/graph-schema.json}")
    private String schemaFile;

    @Value("${graph.profile-file:data/ontology-profile.json}")
    private String profileFile;

    @Value("${app.metadata-file:data/file-metadata.json}")
    private String metadataFile;

    @Value("${app.auth.users-file:data/users.json}")
    private String usersFile;

    @Value("${app.upload-dir:files}")
    private String uploadDir;

    private final AtomicReference<Task> current = new AtomicReference<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "backup-worker");
        t.setDaemon(true);
        return t;
    });

    public BackupService(GraphQueryService queryService, SchemaService schemaService, ObjectMapper mapper) {
        this.queryService = queryService;
        this.schemaService = schemaService;
        this.mapper = mapper;
    }

    // ---------------- 任务控制 ----------------

    /** 启动备份（异步），同一时间仅允许一个任务 */
    public synchronized Map<String, Object> startBackup() {
        Task t = current.get();
        if (t != null && "running".equals(t.status)) {
            throw new ApiException(409, "已有备份任务进行中，请等待完成");
        }
        Task task = new Task("bk-" + System.currentTimeMillis());
        current.set(task);
        executor.submit(() -> runBackup(task));
        log.info("backup started: {}", task.id);
        return Map.of("taskId", task.id);
    }

    /** 查询进度（taskId 不匹配或无任务 → 404） */
    public Map<String, Object> progress(String taskId) {
        Task t = current.get();
        if (t == null || !t.id.equals(taskId)) {
            throw new ApiException(404, "task not found: " + taskId);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("taskId", t.id);
        m.put("status", t.status);
        m.put("phase", t.phase);
        m.put("detail", t.detail);
        m.put("percent", t.percent);
        m.put("error", t.error);
        m.put("backupName", t.backupName);
        return m;
    }

    // ---------------- 备份执行 ----------------

    private void runBackup(Task task) {
        Path tmpDir = null;
        try {
            Files.createDirectories(backupPath());
            tmpDir = Files.createDirectories(backupPath().resolve(".tmp-" + task.id));
            Files.createDirectories(tmpDir.resolve("graph"));

            long nodeCount = exportNodes(task, tmpDir.resolve("graph/nodes.jsonl"));
            long relCount = exportRels(task, tmpDir.resolve("graph/rels.jsonl"));
            long fileCount = copyDataAndFiles(task, tmpDir);
            String name = zipAll(task, tmpDir, nodeCount, relCount, fileCount);

            task.backupName = name;
            task.phase = "done";
            task.detail = "备份完成";
            task.percent = 100;
            task.status = "success";
            log.info("backup finished: {} -> {} (n={} r={} f={} v={})",
                    task.id, name, nodeCount, relCount, fileCount, schemaService.version());
        } catch (Exception e) {
            task.status = "failed";
            task.error = e.getMessage();
            log.error("backup failed: {}", task.id, e);
        } finally {
            if (tmpDir != null) {
                deleteRecursively(tmpDir);
            }
        }
    }

    /** 导出全部节点：每行 {"eid":..,"label":..,"props":{..}}（eid 供恢复时关联关系两端） */
    private long exportNodes(Task task, Path outFile) throws IOException {
        task.phase = "nodes";
        long total = scalar("MATCH (n) RETURN count(n) AS c");
        task.detail = "导出节点 0/" + total;
        long count = 0;
        try (BufferedWriter w = Files.newBufferedWriter(outFile, StandardCharsets.UTF_8)) {
            long skip = 0;
            while (true) {
                List<Map<String, Object>> rows = queryRows(
                        "MATCH (n) RETURN labels(n)[0] AS label, elementId(n) AS eid, properties(n) AS props "
                                + "ORDER BY elementId(n) SKIP $skip LIMIT $limit",
                        Map.of("skip", skip, "limit", BATCH));
                if (rows.isEmpty()) {
                    break;
                }
                for (Map<String, Object> row : rows) {
                    Map<String, Object> line = new LinkedHashMap<>();
                    line.put("eid", row.get("eid"));
                    line.put("label", row.get("label"));
                    line.put("props", row.get("props"));
                    w.write(mapper.writeValueAsString(line));
                    w.newLine();
                }
                count += rows.size();
                skip += rows.size();
                task.detail = "导出节点 " + count + "/" + total;
                task.percent = (int) (40.0 * count / Math.max(total, 1));
            }
        }
        return count;
    }

    /** 导出全部关系：每行 {"type":..,"fromLabel":..,"fromEid":..,"toLabel":..,"toEid":..,"props":{..}} */
    private long exportRels(Task task, Path outFile) throws IOException {
        task.phase = "rels";
        long total = scalar("MATCH ()-[r]->() RETURN count(r) AS c");
        task.detail = "导出关系 0/" + total;
        long count = 0;
        try (BufferedWriter w = Files.newBufferedWriter(outFile, StandardCharsets.UTF_8)) {
            long skip = 0;
            while (true) {
                List<Map<String, Object>> rows = queryRows(
                        "MATCH (a)-[r]->(b) RETURN type(r) AS type, properties(r) AS props, "
                                + "elementId(a) AS fromEid, labels(a)[0] AS fromLabel, "
                                + "elementId(b) AS toEid, labels(b)[0] AS toLabel "
                                + "ORDER BY elementId(r) SKIP $skip LIMIT $limit",
                        Map.of("skip", skip, "limit", BATCH));
                if (rows.isEmpty()) {
                    break;
                }
                for (Map<String, Object> row : rows) {
                    w.write(mapper.writeValueAsString(new LinkedHashMap<>(row)));
                    w.newLine();
                }
                count += rows.size();
                skip += rows.size();
                task.detail = "导出关系 " + count + "/" + total;
                task.percent = 40 + (int) (40.0 * count / Math.max(total, 1));
            }
        }
        return count;
    }

    /** 复制 data 四个 JSON（schema/profile/文件元数据/用户，存在才复制）+ 上传文件目录到临时目录，返回复制的文件数 */
    private long copyDataAndFiles(Task task, Path tmpDir) throws IOException {
        task.phase = "files";
        List<Path> sources = new ArrayList<>();
        for (String cfg : List.of(schemaFile, profileFile, metadataFile, usersFile)) {
            Path p = Path.of(cfg).toAbsolutePath().normalize();
            if (Files.exists(p)) {
                sources.add(p);
            }
        }
        Path srcDir = Path.of(uploadDir).toAbsolutePath().normalize();
        if (Files.isDirectory(srcDir)) {
            try (Stream<Path> walk = Files.walk(srcDir)) {
                walk.filter(Files::isRegularFile).forEach(sources::add);
            }
        }
        long total = sources.size();
        task.detail = "复制文件 0/" + total;
        long count = 0;
        for (Path src : sources) {
            Path dest;
            if (src.startsWith(srcDir) && Files.isDirectory(srcDir)) {
                dest = tmpDir.resolve("files").resolve(srcDir.relativize(src).toString());
            } else {
                dest = tmpDir.resolve("data").resolve(src.getFileName().toString());
            }
            Files.createDirectories(dest.getParent());
            Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
            count++;
            task.detail = "复制文件 " + count + "/" + total;
            task.percent = 80 + (int) (10.0 * count / Math.max(total, 1));
        }
        return count;
    }

    /** 压缩临时目录为正式备份 zip（先 .tmp 后 rename），返回文件名 */
    private String zipAll(Task task, Path tmpDir, long nodeCount, long relCount, long fileCount) throws IOException {
        task.phase = "zip";
        List<Path> entries;
        try (Stream<Path> walk = Files.walk(tmpDir)) {
            entries = walk.filter(Files::isRegularFile).sorted().toList();
        }
        long total = entries.size();
        String name = String.format("backup-%s-n%d-r%d-f%d-v%d.zip",
                LocalDateTime.now().format(STAMP), nodeCount, relCount, fileCount, schemaService.version());
        Path zipTmp = backupPath().resolve(name + ".tmp");
        try {
            try (ZipOutputStream zos = new ZipOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(zipTmp)))) {
                long count = 0;
                for (Path p : entries) {
                    zos.putNextEntry(new ZipEntry(tmpDir.relativize(p).toString().replace('\\', '/')));
                    Files.copy(p, zos);
                    zos.closeEntry();
                    count++;
                    task.detail = "压缩打包 " + count + "/" + total;
                    task.percent = 90 + (int) (10.0 * count / Math.max(total, 1));
                }
            }
            Files.move(zipTmp, backupPath().resolve(name),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(zipTmp);
        }
        return name;
    }

    // ---------------- 列表 / 删除 / 下载 ----------------

    /** 备份列表 = 扫描目录 + 文件名解析（按时间倒序；目录不存在视为空） */
    public Map<String, Object> list() {
        List<Map<String, Object>> backups = new ArrayList<>();
        if (!Files.isDirectory(backupPath())) {
            return Map.of("backups", backups);
        }
        try (Stream<Path> s = Files.list(backupPath())) {
            for (Path p : s.filter(Files::isRegularFile).toList()) {
                Matcher m = NAME_RE.matcher(p.getFileName().toString());
                if (!m.matches()) {
                    continue;
                }
                Map<String, Object> b = new LinkedHashMap<>();
                b.put("name", m.group(0));
                b.put("time", prettyTime(m.group(1)));
                try {
                    b.put("size", Files.size(p));
                } catch (IOException e) {
                    b.put("size", 0L);
                }
                b.put("nodeCount", Long.parseLong(m.group(2)));
                b.put("relCount", Long.parseLong(m.group(3)));
                b.put("fileCount", Long.parseLong(m.group(4)));
                b.put("schemaVersion", Long.parseLong(m.group(5)));
                backups.add(b);
            }
        } catch (IOException e) {
            throw new ApiException(500, "扫描备份目录失败: " + e.getMessage());
        }
        backups.sort(Comparator.comparing(b -> String.valueOf(b.get("name")), Comparator.reverseOrder()));
        return Map.of("backups", backups);
    }

    /** 删除备份（文件名校验防路径穿越） */
    public Map<String, Object> delete(String name) {
        Path p = requireBackup(name);
        try {
            Files.delete(p);
        } catch (IOException e) {
            throw new ApiException(500, "删除备份失败: " + e.getMessage());
        }
        log.info("backup deleted: {}", name);
        return Map.of("deleted", name);
    }

    /** 下载用路径（校验 + 存在性） */
    public Path requireBackup(String name) {
        if (name == null || !NAME_RE.matcher(name).matches()) {
            throw new ApiException(400, "非法备份文件名: " + name);
        }
        Path p = backupPath().resolve(name).normalize();
        if (!p.startsWith(backupPath())) {
            throw new ApiException(400, "非法备份文件名: " + name);
        }
        if (!Files.isRegularFile(p)) {
            throw new ApiException(404, "备份不存在: " + name);
        }
        return p;
    }

    // ---------------- 内部 ----------------

    private List<Map<String, Object>> queryRows(String cypher, Map<String, Object> params) {
        return queryService.query(cypher, params).rows();
    }

    private long scalar(String cypher) {
        List<Map<String, Object>> rows = queryRows(cypher, null);
        return ((Number) rows.get(0).get("c")).longValue();
    }

    private static String prettyTime(String stamp) {
        // 20260820-153045 -> 2026-08-20 15:30:45
        return stamp.replaceAll("^(\\d{4})(\\d{2})(\\d{2})-(\\d{2})(\\d{2})(\\d{2})$",
                "$1-$2-$3 $4:$5:$6");
    }

    private static void deleteRecursively(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignore) {
                    // 清理失败不影响主流程（下次启动可手工清理）
                }
            });
        } catch (IOException ignore) {
            // 目录不存在等
        }
    }

    private Path backupPath() {
        return Path.of(backupDir).toAbsolutePath().normalize();
    }
}
