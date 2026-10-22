package com.conca.ontology.skill.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.conca.ontology.shared.web.ApiException;
import com.conca.ontology.skill.SkillAnalysisStore;
import com.conca.ontology.skill.dto.SkillAnalysis;
import com.conca.ontology.skill.dto.SkillAnalysis.ShellCmd;
import com.conca.ontology.skill.dto.SkillAnalysis.ToolHit;
import com.conca.ontology.skill.dto.SkillAnalysis.ToolUsage;
import com.conca.ontology.agent.service.AgentChatService;

import io.agentscope.harness.agent.HarnessAgent;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Skills 管理 / 检测引擎（ADMIN 功能，入口 /eval 页面）：
 * - 技能清单：以 agent 已装配技能仓库为准（与 chat 页技能 chips 单一事实来源一致），
 *   agent 未装配时退化为磁盘扫描 skills-dir + workspace/skills 解析 frontmatter。
 * - 磁盘定位：按 SKILL.md frontmatter 的 name 匹配目录（目录名可含版本后缀如 tongshu-1.0.0，
 *   与技能名不同，因此不能以目录名作技能名）。
 * - 检测：对 SKILL.md + 全部文本资源做工具（系统工具反向比对）/ API / Shell 命令三路提取，
 *   结果经 SkillAnalysisStore 落盘 data/skill-analysis.json。
 *
 * @author tianbaoxing
 * @date 2026-09-16
 */
@Service
public class SkillAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(SkillAnalysisService.class);

    /** SKILL.md frontmatter 的 name/description 提取（正文描述为空时用）；name 是技能唯一标识 */
    private static final Pattern FM_NAME = Pattern.compile("(?m)^name:\\s*\"?([^\"\\n]+)\"?\\s*$");
    private static final Pattern FM_DESC = Pattern.compile("(?m)^description:\\s*\"?([^\"\\n]*)\"?\\s*$");
    /** 代码块围栏（``` 或 ~~~），用于标记工具名是否出现在代码块内 */
    private static final Pattern FENCE = Pattern.compile("^\\s*(```|~~~)");
    /** API 路径提取：/api/ 开头的相对路径（含 /api/llm/** 之类通配，保留中文等即停止） */
    private static final Pattern API_PATH = Pattern.compile("/api/[\\w\\-/{}.:*]+");
    /** 文本资源扩展名（参与检测的文件）；其余按二进制跳过 */
    private static final Set<String> TEXT_EXTS = Set.of(
            "md", "py", "sh", "bash", "txt", "json", "yml", "yaml", "xml", "html",
            "js", "ts", "java", "properties", "env", "cfg", "conf", "ini", "csv", "ps1", "bat", "cmd");

    /** 内置 shell 命令词表（Windows + Unix 常用；命中启发式：行首或 & | ; 分隔后的首词） */
    private static final Set<String> SHELL_CMDS = Set.of(
            "curl", "wget", "python", "python3", "pip", "pip3", "node", "npm", "npx", "yarn",
            "mkdir", "cd", "type", "copy", "del", "erase", "dir", "findstr", "where", "echo",
            "powershell", "powershell.exe", "cmd", "cmd.exe", "java", "mvn", "mvnw", "git",
            "cat", "head", "tail", "ls", "grep", "sed", "awk", "chmod", "chown", "export",
            "set", "setx", "touch", "rm", "mv", "cp", "pwd", "ps", "kill", "tree", "tasklist");

    /** 可执行脚本扩展名（判断技能是否带执行脚本，如 tongshu_query.py） */
    private static final Set<String> SCRIPT_EXTS = Set.of("py", "sh", "js", "ps1", "bat", "cmd");

    private final ObjectProvider<HarnessAgent> agentProvider;
    private final AgentChatService agentChatService;
    private final SkillAnalysisStore store;

    @Value("${app.agent.skills-dir:}")
    private String skillsDir;

    @Value("${app.agent.workspace-dir:}")
    private String workspaceDir;

    public SkillAnalysisService(ObjectProvider<HarnessAgent> agentProvider,
            AgentChatService agentChatService, SkillAnalysisStore store) {
        this.agentProvider = agentProvider;
        this.agentChatService = agentChatService;
        this.store = store;
    }

    // ============================== 技能清单 / 定位 ==============================

    /**
     * 技能列表（含磁盘目录定位 + 已分析摘要）。来源优先 agent 技能仓库（与 chat 页一致），
     * agent 未装配（未配置 key）时退回磁盘扫描。返回元素：
     * {name, description, dir, files, summary}，summary 为 store 中已有分析（无则 null）。
     */
    public List<Map<String, Object>> listSkills() {
        Map<String, Path> byName = scanSkillDirs();
        Map<String, String> descFromRepo = new LinkedHashMap<>();
        List<String> order = new ArrayList<>();
        if (agentProvider.getIfAvailable() != null) {
            for (Map<String, String> s : agentChatService.listSkills()) {
                descFromRepo.put(s.get("name"), s.getOrDefault("description", ""));
                order.add(s.get("name"));
            }
        }
        // agent 未装配 → 磁盘扫描兜底（按目录名自然序）
        if (order.isEmpty()) {
            order.addAll(byName.keySet());
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (String name : order) {
            Path dir = byName.get(name);
            SkillAnalysis sa = store.get(name);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", name);
            item.put("description", descFromRepo.getOrDefault(name, dir != null ? parseDescription(dir) : ""));
            item.put("dir", dir == null ? null : dir.toString());
            item.put("scripts", dir != null ? scanScripts(dir) : List.of());
            item.put("summary", filtered(sa));
            item.put("keyInfo", sa != null && sa.keyInfo() != null ? sa.keyInfo() : List.of());
            // stale：已分析（contentHash 非空）且当前文件哈希与存储不一致 → 检测结果过期，前端按钮变红 + 顶部提示
            item.put("stale", sa != null && sa.contentHash() != null && dir != null
                    && !sa.contentHash().equals(currentHash(dir)));
            out.add(item);
        }
        return out;
    }

    /**
     * 保存技能识别关键词（判断 LLM 是否真实执行该 skill 的特征指纹，逗号分隔多个）：
     * 独立于检测结果；若该技能尚无分析结果，先落一个 keyInfo-only 记录（contentHash 为空，
     * 列表仍显示「未检测」）。关键词去空白、去重。
     */
    public Map<String, Object> saveKeyInfo(String name, List<String> keywords) {
        locateSkill(name); // 技能不存在 404
        List<String> clean = keywords == null ? List.of()
                : keywords.stream().map(String::trim).filter(k -> !k.isBlank()).distinct().toList();
        SkillAnalysis prev = store.get(name);
        SkillAnalysis next = prev == null
                ? new SkillAnalysis(null, null, List.of(),
                        new SkillAnalysis.ToolUsage(List.of(), List.of()), List.of(), List.of(), clean,
                        List.of(), List.of(), List.of())
                : new SkillAnalysis(prev.analyzedAt(), prev.contentHash(), prev.scannedFiles(),
                        prev.tools(), prev.apis(), prev.shellCommands(), clean,
                        nz(prev.hiddenTools()), nz(prev.hiddenApis()), nz(prev.hiddenShellCommands()));
        store.put(name, next);
        return Map.of("name", name, "keyInfo", clean);
    }

    /**
     * 删除（忽略）检测项：将工具 / API / Shell 命令加入忽略清单并自动落盘。
     * 用于过滤太常见的命中（如 cd、echo 等），忽略项跨重新检测保留；
     * 响应中 summary 已剔除被忽略项，hidden 返回忽略清单供前端提示。
     * 参数列表为空则对应类别不做变更。
     */
    public Map<String, Object> hideItems(String name, List<String> tools, List<String> apis,
            List<String> shellCommands) {
        locateSkill(name); // 技能不存在 404
        SkillAnalysis prev = store.get(name);
        List<String> hTools = new ArrayList<>(nz(prev == null ? null : prev.hiddenTools()));
        List<String> hApis = new ArrayList<>(nz(prev == null ? null : prev.hiddenApis()));
        List<String> hCmds = new ArrayList<>(nz(prev == null ? null : prev.hiddenShellCommands()));
        mergeDistinct(hTools, tools);
        mergeDistinct(hApis, apis);
        mergeDistinct(hCmds, shellCommands);
        SkillAnalysis next = prev == null
                ? new SkillAnalysis(null, null, List.of(),
                        new SkillAnalysis.ToolUsage(List.of(), List.of()), List.of(), List.of(), List.of(),
                        hTools, hApis, hCmds)
                : new SkillAnalysis(prev.analyzedAt(), prev.contentHash(), prev.scannedFiles(),
                        prev.tools(), prev.apis(), prev.shellCommands(), nz(prev.keyInfo()),
                        hTools, hApis, hCmds);
        store.put(name, next);
        return Map.of("name", name,
                "summary", filtered(next),
                "hidden", Map.of("tools", hTools, "apis", hApis, "shellCommands", hCmds));
    }

    /** 单技能详情：基本信息 + 文件清单 + 现有分析摘要 + stale（文件已变更提示）。不存在 404。 */
    public Map<String, Object> skillDetail(String name) {
        Path dir = locateSkill(name);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", name);
        out.put("description", parseDescription(dir));
        out.put("dir", dir.toString());
        out.put("files", listFiles(dir));
        SkillAnalysis sa = store.get(name);
        out.put("summary", filtered(sa));
        out.put("stale", sa != null && !sa.contentHash().equals(currentHash(dir)));
        return out;
    }

    /** 读取技能内资源文件（防路径穿越：规范化后必须在技能根目录内）。不存在 404。 */
    public Map<String, Object> readResource(String name, String relativePath) {
        Path dir = locateSkill(name);
        if (relativePath == null || relativePath.isBlank()) {
            throw new ApiException(400, "path 不能为空（技能根目录相对路径，如 scripts/env.py）");
        }
        Path resolved = dir.resolve(relativePath).normalize();
        if (!resolved.startsWith(dir)) {
            throw new ApiException(400, "非法路径（不允许越出技能目录）: " + relativePath);
        }
        if (!Files.isRegularFile(resolved)) {
            throw new ApiException(404, "文件不存在: " + relativePath);
        }
        try {
            return Map.of(
                    "path", relativePath,
                    "size", Files.size(resolved),
                    "content", Files.readString(resolved, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ApiException(500, "读取文件失败: " + e.getMessage());
        }
    }

    // ============================== 检测引擎 ==============================

    /**
     * 对指定技能执行三路检测（工具 / API / Shell）并落盘，返回完整结果。
     * 工具清单实时取自 agent Toolkit（单一事实来源）；agent 未装配时 used/notUsed 为空并标记 agentAvailable=false。
     */
    public Map<String, Object> analyze(String name) {
        Path dir = locateSkill(name);
        List<Path> files = collectTextFiles(dir);
        List<String> relPaths = new ArrayList<>();
        // 扫描文本由统一方法构建（正斜杠相对路径），与 currentHash 完全一致 → stale 判断才可靠
        String text = buildScanText(dir, files, relPaths);
        String hash = sha256(text);

        // 工具检测：系统工具名反向比对（词边界，排除名字中的 _ -）
        List<Map<String, String>> sysTools = agentChatService.listTools();
        List<SkillAnalysis.ToolHit> used = new ArrayList<>();
        List<String> notUsed = new ArrayList<>();
        for (Map<String, String> t : sysTools) {
            String toolName = t.get("name");
            if (toolName == null || toolName.isBlank()) {
                continue;
            }
            Hit h = countHits(text, toolName);
            if (h.count > 0) {
                used.add(new SkillAnalysis.ToolHit(toolName, h.count, h.inCodeBlock));
            } else {
                notUsed.add(toolName);
            }
        }
        used.sort(Comparator.comparing(SkillAnalysis.ToolHit::name));

        // API 提取：/api/ 开头的相对路径，去尾 * 与 /，去重保序
        Set<String> apis = new LinkedHashSet<>();
        Matcher am = API_PATH.matcher(text);
        while (am.find()) {
            String p = am.group().replaceAll("\\*+$", "").replaceAll("/+$", "");
            if (!p.isBlank()) {
                apis.add(p);
            }
        }

        // Shell 命令提取：行按 & | ; 切分，取片段首词
        Map<String, Integer> cmdCnt = new LinkedHashMap<>();
        for (String line : text.split("\\R")) {
            for (String part : line.split("[;&|]")) {
                String t = part.trim().replaceFirst("^[>\\-*\\d.\\s`]+", "");
                if (t.isEmpty() || t.startsWith("#") || t.startsWith("//") || t.startsWith("<!--")) {
                    continue;
                }
                String first = t.split("\\s+")[0].toLowerCase().trim();
                if (!first.isBlank() && SHELL_CMDS.contains(first)) {
                    cmdCnt.merge(first, 1, Integer::sum);
                }
            }
        }
        List<SkillAnalysis.ShellCmd> cmds = new ArrayList<>();
        cmdCnt.forEach((c, n) -> cmds.add(new SkillAnalysis.ShellCmd(c, n)));
        cmds.sort(Comparator.comparing(SkillAnalysis.ShellCmd::command));

        SkillAnalysis prev = store.get(name);
        List<String> keyInfo = prev != null && prev.keyInfo() != null ? prev.keyInfo() : List.of();
        SkillAnalysis sa = new SkillAnalysis(LocalDateTime.now().toString(),
                hash, relPaths,
                new SkillAnalysis.ToolUsage(used, notUsed),
                new ArrayList<>(apis), cmds, keyInfo,
                nz(prev == null ? null : prev.hiddenTools()),
                nz(prev == null ? null : prev.hiddenApis()),
                nz(prev == null ? null : prev.hiddenShellCommands()));
        store.put(name, sa);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", name);
        out.put("agentAvailable", !sysTools.isEmpty());
        out.put("summary", sa);
        return out;
    }

    /** 命中统计：全文总次数 + 是否至少一次在代码块围栏内 */
    private static Hit countHits(String text, String token) {
        Pattern p = Pattern.compile("(?<![\\w-])" + Pattern.quote(token) + "(?![\\w-])");
        int count = 0;
        boolean inCode = false;
        boolean fence = false;
        for (String line : text.split("\\R")) {
            if (FENCE.matcher(line).find()) {
                fence = !fence;
                continue;
            }
            Matcher m = p.matcher(line);
            int lineHits = 0;
            while (m.find()) {
                lineHits++;
            }
            if (lineHits > 0) {
                count += lineHits;
                if (fence) {
                    inCode = true;
                }
            }
        }
        return new Hit(count, inCode);
    }

    private record Hit(int count, boolean inCodeBlock) {
    }

    // ============================== 忽略清单 ==============================

    /** 追加去重（trim 后非空且未包含才加入），add 为 null 时不动作 */
    private static void mergeDistinct(List<String> target, List<String> add) {
        if (add == null) {
            return;
        }
        for (String s : add) {
            String t = s == null ? "" : s.trim();
            if (!t.isEmpty() && !target.contains(t)) {
                target.add(t);
            }
        }
    }

    /** null 安全：旧数据文件缺 hidden* 字段时按空清单处理 */
    private static List<String> nz(List<String> l) {
        return l == null ? List.of() : l;
    }

    /**
     * 响应视图：从 tools.used / apis / shellCommands 中剔除已忽略项，
     * 并携带 hiddenTools / hiddenApis / hiddenShellCommands 供前端提示「已忽略 N 项」。
     * 存储层保持完整检测结果（重新检测后隐藏项仍保留）。
     */
    private static SkillAnalysis filtered(SkillAnalysis sa) {
        if (sa == null) {
            return null;
        }
        List<String> ht = nz(sa.hiddenTools());
        List<String> ha = nz(sa.hiddenApis());
        List<String> hsc = nz(sa.hiddenShellCommands());
        SkillAnalysis.ToolUsage tu = sa.tools();
        SkillAnalysis.ToolUsage ntu = tu == null ? new SkillAnalysis.ToolUsage(List.of(), List.of())
                : new SkillAnalysis.ToolUsage(tu.used() == null ? List.of()
                        : tu.used().stream().filter(t -> !ht.contains(t.name())).toList(),
                        tu.notUsed() == null ? List.of() : tu.notUsed());
        return new SkillAnalysis(sa.analyzedAt(), sa.contentHash(),
                sa.scannedFiles() == null ? List.of() : sa.scannedFiles(), ntu,
                sa.apis() == null ? List.of() : sa.apis().stream().filter(a -> !ha.contains(a)).toList(),
                sa.shellCommands() == null ? List.of()
                        : sa.shellCommands().stream().filter(c -> !hsc.contains(c.command())).toList(),
                nz(sa.keyInfo()), ht, ha, hsc);
    }

    // ============================== 磁盘工具 ==============================

    /** 扫描全局 skills-dir + workspace/skills，返回 技能名 → 技能根目录（frontmatter name 为准，首现优先） */
    private Map<String, Path> scanSkillDirs() {
        Map<String, Path> map = new TreeMap<>();
        for (Path root : skillRoots()) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> s = Files.list(root)) {
                for (Path sub : s.filter(Files::isDirectory).toList()) {
                    Path md = sub.resolve("SKILL.md");
                    if (!Files.isRegularFile(md)) {
                        continue;
                    }
                    String name = parseName(md);
                    if (name == null || name.isBlank()) {
                        continue;
                    }
                    map.putIfAbsent(name, sub);
                }
            } catch (IOException e) {
                log.warn("扫描技能目录失败（{}）: {}", root, e.getMessage());
            }
        }
        return map;
    }

    /** 按技能名定位技能根目录，不存在 404 */
    private Path locateSkill(String name) {
        Path dir = scanSkillDirs().get(name);
        if (dir == null) {
            throw new ApiException(404, "技能不存在: " + name);
        }
        return dir;
    }

    /** 候选技能根目录（全局 skills-dir 在前，workspace/skills 在后） */
    private List<Path> skillRoots() {
        List<Path> roots = new ArrayList<>();
        if (skillsDir != null && !skillsDir.isBlank()) {
            roots.add(Path.of(skillsDir));
        }
        if (workspaceDir != null && !workspaceDir.isBlank()) {
            roots.add(Path.of(workspaceDir, "skills"));
        }
        return roots;
    }

    /** 技能目录下参与检测的文件（递归；SKILL.md 与文本扩展名；按相对路径排序） */
    private List<Path> collectTextFiles(Path dir) {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> s = Files.walk(dir)) {
            s.filter(Files::isRegularFile)
                    .filter(p -> isTextFile(dir.relativize(p).getFileName().toString()))
                    .forEach(files::add);
        } catch (IOException e) {
            log.warn("扫描技能文件失败（{}）: {}", dir, e.getMessage());
        }
        files.sort(Comparator.comparing(p -> dir.relativize(p).toString()));
        return files;
    }

    /** 技能目录下文件清单（含相对路径/大小/是否 SKILL.md，按路径排序） */
    private List<Map<String, Object>> listFiles(Path dir) {
        List<Map<String, Object>> out = new ArrayList<>();
        try (Stream<Path> s = Files.walk(dir)) {
            s.filter(Files::isRegularFile).forEach(f -> {
                String rel = dir.relativize(f).toString().replace('\\', '/');
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("path", rel);
                m.put("size", safeSize(f));
                m.put("isSkillMd", rel.equals("SKILL.md"));
                out.add(m);
            });
        } catch (IOException e) {
            log.warn("扫描技能文件清单失败（{}）: {}", dir, e.getMessage());
        }
        out.sort(Comparator.comparing(m -> (String) m.get("path")));
        return out;
    }

    /** 技能目录下可执行脚本文件（递归扫描，相对路径，按路径排序），用于列表「执行脚本」列 */
    private List<String> scanScripts(Path dir) {
        List<String> out = new ArrayList<>();
        try (Stream<Path> s = Files.walk(dir)) {
            s.filter(Files::isRegularFile).forEach(f -> {
                String rel = dir.relativize(f).toString().replace('\\', '/');
                String lower = rel.toLowerCase();
                int dot = lower.lastIndexOf('.');
                String ext = dot >= 0 ? lower.substring(dot + 1) : "";
                if (SCRIPT_EXTS.contains(ext)) {
                    out.add(rel);
                }
            });
        } catch (IOException e) {
            log.warn("扫描技能脚本失败（{}）: {}", dir, e.getMessage());
        }
        out.sort(String::compareTo);
        return out;
    }

    private static boolean isTextFile(String fileName) {
        String lower = fileName.toLowerCase();
        int dot = lower.lastIndexOf('.');
        String ext = dot >= 0 ? lower.substring(dot + 1) : "";
        // 无扩展名（如 env 模板、Makefile）也按文本处理
        return ext.isEmpty() || TEXT_EXTS.contains(ext);
    }

    private static long safeSize(Path f) {
        try {
            return Files.size(f);
        } catch (IOException e) {
            return -1;
        }
    }

    /** 当前技能全部文本内容哈希（用于 stale 判断：文件变更后与 store 中 contentHash 不一致） */
    private String currentHash(Path dir) {
        return sha256(buildScanText(dir, collectTextFiles(dir), null));
    }

    /**
     * 构建扫描文本（统一入口，analyze 与 currentHash 共用保证哈希一致）：
     * 每文件以正斜杠相对路径为标题头 + UTF-8 内容，按 collectTextFiles 排序顺序拼接。
     */
    private String buildScanText(Path dir, List<Path> files, List<String> relPaths) {
        StringBuilder all = new StringBuilder();
        for (Path f : files) {
            String rel = dir.relativize(f).toString().replace('\\', '/');
            if (relPaths != null) {
                relPaths.add(rel);
            }
            try {
                all.append("===== ").append(rel).append(" =====\n")
                        .append(Files.readString(f, StandardCharsets.UTF_8)).append("\n");
            } catch (IOException e) {
                if (relPaths != null) {
                    relPaths.remove(rel);
                }
                log.warn("读取技能文件失败（跳过）: {}", f);
            }
        }
        return all.toString();
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return "hash-error";
        }
    }

    // ============================== frontmatter 解析 ==============================

    private static String parseName(Path md) {
        try {
            String head = headOf(Files.readString(md, StandardCharsets.UTF_8));
            Matcher m = FM_NAME.matcher(head);
            return m.find() ? m.group(1).trim() : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static String parseDescription(Path dir) {
        Path md = dir.resolve("SKILL.md");
        if (!Files.isRegularFile(md)) {
            return "";
        }
        try {
            String head = headOf(Files.readString(md, StandardCharsets.UTF_8));
            Matcher m = FM_DESC.matcher(head);
            return m.find() ? m.group(1).trim() : "";
        } catch (IOException e) {
            return "";
        }
    }

    /** 仅取 frontmatter 头部（--- 之后非 --- 的部分），避免正文误匹配 */
    private static String headOf(String content) {
        if (!content.startsWith("---")) {
            return "";
        }
        int end = content.indexOf("\n---", 3);
        return end > 0 ? content.substring(0, end) : content;
    }
}
