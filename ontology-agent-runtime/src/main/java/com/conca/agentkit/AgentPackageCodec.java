package com.conca.agentkit;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 导出包编解码（L1 平台包，对标 Dify DSL 的平台内可迁移格式）：
 *
 * agent-package.zip
 * ├── agent.json        # AgentDefinition.forExport()（apiKeyRef=${AGENT_API_KEY} 占位）
 * ├── MANIFEST.json     # 包格式版本/导出方/最低 harness 版本/文件清单+sha256
 * ├── skills/           # 该智能体勾选的技能文件夹原样拷贝（与 Anthropic Agent Skills 同构）
 * └── subagents/        # 声明式子智能体 .md 原样拷贝
 *
 * 读取侧带 zip-slip 防护与 sha256 完整性校验（导出 jar 追加后的冒烟自检也走这里）。
 */
public final class AgentPackageCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int FORMAT_VERSION = 1;
    private static final String ENTRY_AGENT = "agent.json";
    private static final String ENTRY_MANIFEST = "MANIFEST.json";
    private static final String DIR_SKILLS = "skills/";
    private static final String DIR_SUBAGENTS = "subagents/";

    /** 包内清单（写读共用结构） */
    public record Manifest(int formatVersion, String exportedAt, String exportedBy,
            String minHarnessVersion, List<FileEntry> files) {
        public record FileEntry(String path, long size, String sha256) {}
    }

    /** 读取产物：定义 + 解包后的工作区根（skills/、subagents/ 已就位） */
    public record PackageBundle(AgentDefinition definition, Path workspaceRoot, Manifest manifest) {}

    private AgentPackageCodec() {
    }

    /**
     * 打包：定义（导出视图）+ 工作区（skills/、subagents/）→ zip。
     * 只收集 skills/ 与 subagents/ 两个子树，绝不带入密钥、状态与会话日志。
     */
    public static void write(Path zipPath, AgentDefinition def, Path workspace,
            String exportedBy, String minHarnessVersion) throws IOException {
        AgentDefinition exportDef = def.forExport();
        List<Manifest.FileEntry> entries = new ArrayList<>();
        Path absWorkspace = workspace.toAbsolutePath().normalize();

        try (ZipOutputStream zip = new ZipOutputStream(
                Files.newOutputStream(zipPath), StandardCharsets.UTF_8)) {
            // agent.json + MANIFEST 占位先写（MANIFEST 含全部文件摘要，需要先收集，最后重写）
            put(zip, ENTRY_AGENT, MAPPER.writeValueAsBytes(exportDef));
            put(zip, ENTRY_MANIFEST, new byte[0]);

            collectDir(zip, entries, absWorkspace.resolve("skills"), absWorkspace, DIR_SKILLS);
            collectDir(zip, entries, absWorkspace.resolve("subagents"), absWorkspace, DIR_SUBAGENTS);

            Manifest manifest = new Manifest(FORMAT_VERSION, Instant.now().toString(),
                    exportedBy == null ? "" : exportedBy,
                    minHarnessVersion == null ? "" : minHarnessVersion, entries);
            // 替换 MANIFEST 占位：zip 条目不可原位更新，收尾重写一次
            replaceEntry(zipPath, ENTRY_MANIFEST, MAPPER.writeValueAsBytes(manifest));
        }
    }

    /** 收集目录子树入包（相对 workspace 的路径即包内路径） */
    private static void collectDir(ZipOutputStream zip, List<Manifest.FileEntry> entries,
            Path dir, Path workspaceRoot, String prefix) throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            List<Path> files = walk.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
            for (Path f : files) {
                String rel = workspaceRoot.relativize(f).toString().replace('\\', '/');
                byte[] data = Files.readAllBytes(f);
                put(zip, rel, data);
                entries.add(new Manifest.FileEntry(rel, data.length, sha256(data)));
            }
        }
    }

    /** 读取：解包（zip-slip 防护）→ sha256 校验 → 返回定义与工作区根 */
    public static PackageBundle read(Path zipPath, Path extractTo) throws IOException {
        Files.createDirectories(extractTo);
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(
                Files.newInputStream(zipPath), StandardCharsets.UTF_8)) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                String name = e.getName();
                if (e.isDirectory() || !allowed(name)) {
                    continue;
                }
                files.put(name, zip.readAllBytes());
            }
        }
        byte[] agentJson = require(files, ENTRY_AGENT);
        byte[] manifestJson = require(files, ENTRY_MANIFEST);
        Manifest manifest = MAPPER.readValue(manifestJson, Manifest.class);
        if (manifest.formatVersion() > FORMAT_VERSION) {
            throw new IOException("导出包格式版本更高（" + manifest.formatVersion()
                    + " > " + FORMAT_VERSION + "），请升级运行时后导入");
        }
        // sha256 完整性校验
        for (Manifest.FileEntry fe : manifest.files()) {
            byte[] data = files.get(fe.path());
            if (data == null || !fe.sha256().equalsIgnoreCase(sha256(data))) {
                throw new IOException("包内文件校验失败：" + fe.path());
            }
        }
        // 落盘（仅 skills/、subagents/、agent.json；MANIFEST 不落盘）
        for (Map.Entry<String, byte[]> f : files.entrySet()) {
            if (ENTRY_MANIFEST.equals(f.getKey()) || ENTRY_AGENT.equals(f.getKey())) {
                continue;
            }
            Path target = safeResolve(extractTo, f.getKey());
            Files.createDirectories(target.getParent());
            Files.write(target, f.getValue());
        }
        AgentDefinition def = MAPPER.readValue(agentJson, AgentDefinition.class);
        return new PackageBundle(def, extractTo, manifest);
    }

    /** 包内路径白名单：仅四个已知入口（密钥/状态/日志结构上不可入包） */
    private static boolean allowed(String name) {
        return ENTRY_AGENT.equals(name) || ENTRY_MANIFEST.equals(name)
                || name.startsWith(DIR_SKILLS) || name.startsWith(DIR_SUBAGENTS);
    }

    private static byte[] require(Map<String, byte[]> files, String name) throws IOException {
        byte[] data = files.get(name);
        if (data == null) {
            throw new IOException("导出包缺少必备文件：" + name);
        }
        return data;
    }

    /** zip-slip 防护：解析后必须仍在目标目录内 */
    private static Path safeResolve(Path root, String entryName) throws IOException {
        Path resolved = root.resolve(entryName).normalize();
        if (!resolved.startsWith(root.toAbsolutePath().normalize())) {
            throw new IOException("包内路径越界：" + entryName);
        }
        return resolved;
    }

    private static void put(ZipOutputStream zip, String name, byte[] data) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
    }

    /** 替换 zip 中同名条目：全量重写（导出仅两阶段各一次，量级小） */
    static void replaceEntry(Path zipPath, String name, byte[] data) throws IOException {
        Path tmp = Files.createTempFile("repack", ".zip");
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zipPath), StandardCharsets.UTF_8);
             ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(tmp), StandardCharsets.UTF_8)) {
            ZipEntry e;
            boolean replaced = false;
            while ((e = in.getNextEntry()) != null) {
                if (name.equals(e.getName())) {
                    put(out, name, data);
                    replaced = true;
                } else {
                    out.putNextEntry(new ZipEntry(e.getName()));
                    in.transferTo(out);
                    out.closeEntry();
                }
            }
            if (!replaced) {
                put(out, name, data);
            }
        }
        try (InputStream src = Files.newInputStream(tmp);
             OutputStream dst = Files.newOutputStream(zipPath)) {
            src.transferTo(dst);
        }
        Files.deleteIfExists(tmp);
    }

    public static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
