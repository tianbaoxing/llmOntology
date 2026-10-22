package com.conca.ontology.skill;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.conca.ontology.skill.dto.SkillAnalysis;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 技能「正确使用」判定（session-detail 页「技能加载」区）：
 * load_skill* 成功只证明内容注入，不证明模型遵循。本判定器以
 * data/skill-analysis.json 的技能特征指纹与会话中「首次成功加载之后的全部
 * assistant 步骤」做匹配，任一通道命中即判「已使用」；首命中 ≤
 * app.skill-usage-immediate-steps 步（默认 2）额外标注「即时遵循」。
 *
 * 四类强证据（仅认结构化工具调用与 thinking，剔除用户忽略项 hidden*）：
 * - api     http_request* 工具入参 path/url 与技能 apis 精确或 {占位符}模板匹配
 * - shell   execute 工具命令按 && ; | 切分子命令，首 token 与 shellCommands 匹配（python≈python3）
 * - script  任意工具入参文本引用技能 scannedFiles 中 scripts/ 下文件（相对路径或文件名）
 * - keyword 加载后 assistant 消息 thinking 包含 keyInfo 关键词（忽略大小写）
 *
 * 窗口语义：首次成功加载 → 会话结束（多次加载即首次加载后全部步骤的并集）；
 * 压缩恢复的 flat 消息无 thinking 时关键词通道自然降级，工具通道不受影响。
 * 判定不抛错：内部异常降级为空结果，不影响详情接口本身。
 *
 * @author tianbaoxing
 * @date 2026-09-16
 */
@Component
public class SkillUsageJudge {

    private static final Logger log = LoggerFactory.getLogger(SkillUsageJudge.class);

    /** load_skill* 输出首行：Successfully loaded skill: <namespacedId> */
    private static final Pattern LOAD_OK = Pattern.compile("Successfully loaded skill:\\s*(\\S+)");
    private static final Pattern LOAD_NAME = Pattern.compile("(?m)^Name:\\s*(.+)$");
    private static final Pattern LOAD_DESC = Pattern.compile("(?m)^Description:\\s*(.+)$");
    /** 复合命令切分：&& 、|| 、; 、| */
    private static final Pattern CMD_SPLIT = Pattern.compile("\\s*(?:&&|\\|\\||;|\\|)\\s*");

    private final SkillAnalysisStore store;
    private final ObjectMapper mapper;

    /** 「即时遵循」阈值：加载后第 N 个 assistant 步骤内首次命中（默认 2） */
    @Value("${app.skill-usage-immediate-steps:2}")
    private int immediateSteps;

    public SkillUsageJudge(SkillAnalysisStore store, ObjectMapper mapper) {
        this.store = store;
        this.mapper = mapper;
    }

    /**
     * 判定入口。入参为 SessionDetailService 已构建的消息明细（assistant 消息含
     * thinking / tools[{name,input,output}]，含压缩恢复的 flat 消息），返回每个
     * 成功加载技能的判定结果（按首次加载顺序）：
     * {name, loadId, loadTimes, description, verdict, reason, immediateFollow,
     *  firstHitStep, evidence[{type, match, fingerprint, tool, step, count}]}
     */
    public List<Map<String, Object>> judge(List<Map<String, Object>> messages) {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            // ---- 1. assistant 步骤序列 + 首次成功加载事件（按技能名归并）----
            List<Map<String, Object>> steps = new ArrayList<>();
            Map<String, SkillLoads> loads = new LinkedHashMap<>();
            for (Map<String, Object> m : messages) {
                if (!"assistant".equals(m.get("role"))) {
                    continue;
                }
                steps.add(m);
                int seq = steps.size();
                for (Object o : (List<?>) m.getOrDefault("tools", List.of())) {
                    if (!(o instanceof Map<?, ?> t)) {
                        continue;
                    }
                    String output = str(t.get("output"));
                    if (!str(t.get("name")).startsWith("load_skill")
                            || output == null || !output.contains("Successfully loaded skill")) {
                        continue;
                    }
                    Matcher ok = LOAD_OK.matcher(output);
                    String id = ok.find() ? ok.group(1).trim() : "";
                    String name = firstGroup(LOAD_NAME, output);
                    if (name == null || name.isBlank()) { // Name 行缺失 → 去掉命名空间后缀
                        int u = id.indexOf('_');
                        name = u > 0 ? id.substring(0, u) : id;
                    }
                    if (name.isBlank()) {
                        continue;
                    }
                    SkillLoads s = loads.computeIfAbsent(name,
                            k -> new SkillLoads(id, firstGroup(LOAD_DESC, output), seq));
                    s.times++;
                }
            }

            // ---- 2. 每个技能：取指纹 → 首次加载后的步骤扫描 → 汇总裁决 ----
            for (Map.Entry<String, SkillLoads> en : loads.entrySet()) {
                out.add(judgeOne(en.getKey(), en.getValue(), steps));
            }
        } catch (Exception e) {
            log.warn("技能使用判定失败（降级为空结果）: {}", e.getMessage(), e);
        }
        return out;
    }

    private Map<String, Object> judgeOne(String name, SkillLoads load, List<Map<String, Object>> steps) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", name);
        out.put("loadId", load.id);
        out.put("loadTimes", load.times);
        out.put("description", load.desc);

        SkillAnalysis sa = store.get(name);
        if (sa == null) {
            return verdict(out, "undeterminable", "no_analysis", null, null);
        }
        Fingerprints fp = Fingerprints.of(sa);
        if (fp.isEmpty()) {
            return verdict(out, "undeterminable", "no_fingerprint", null, null);
        }
        if (load.seq >= steps.size()) {
            return verdict(out, "undeterminable", "no_steps", null, null);
        }

        // 扫描首次加载之后的全部 assistant 步骤，按 (通道, 命中, 指纹, 工具) 去重累计
        Map<String, Evidence> evidence = new LinkedHashMap<>();
        for (int seq = load.seq + 1; seq <= steps.size(); seq++) {
            scanStep(steps.get(seq - 1), seq, fp, evidence);
        }
        if (evidence.isEmpty()) {
            return verdict(out, "unused", null, null, null);
        }
        int firstHitStep = Integer.MAX_VALUE;
        List<Map<String, Object>> list = new ArrayList<>();
        for (Evidence e : evidence.values()) {
            firstHitStep = Math.min(firstHitStep, e.step);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", e.type);
            m.put("match", e.match);
            m.put("fingerprint", e.fingerprint);
            m.put("tool", e.tool);
            m.put("step", e.step);
            m.put("count", e.count);
            list.add(m);
        }
        return verdict(out, "used", null, firstHitStep <= immediateSteps, firstHitStep, list);
    }

    private static Map<String, Object> verdict(Map<String, Object> out, String verdict, String reason,
            Boolean immediateFollow, Integer firstHitStep) {
        return verdict(out, verdict, reason, immediateFollow, firstHitStep, null);
    }

    private static Map<String, Object> verdict(Map<String, Object> out, String verdict, String reason,
            Boolean immediateFollow, Integer firstHitStep, List<Map<String, Object>> evidence) {
        out.put("verdict", verdict);
        out.put("reason", reason);
        out.put("immediateFollow", immediateFollow != null && immediateFollow);
        out.put("firstHitStep", firstHitStep);
        out.put("evidence", evidence == null ? List.of() : evidence);
        return out;
    }

    // ============================== 单步骤证据扫描 ==============================

    @SuppressWarnings("unchecked")
    private void scanStep(Map<String, Object> step, int seq, Fingerprints fp, Map<String, Evidence> out) {
        // 关键词通道：thinking 包含 keyInfo
        String thinking = str(step.get("thinking"));
        for (String kw : fp.keywords) {
            if (thinking != null && containsIgnoreCase(thinking, kw)) {
                add(out, new Evidence("keyword", kw, kw, "thinking", seq));
            }
        }
        for (Object o : (List<Object>) step.getOrDefault("tools", List.of())) {
            if (!(o instanceof Map<?, ?> t)) {
                continue;
            }
            String tool = str(t.get("name"));
            if (tool == null || tool.startsWith("load_skill")) { // 加载调用自身不算证据
                continue;
            }
            String input = str(t.get("input"));
            if (input == null || input.isBlank()) {
                continue;
            }
            // API 通道：http_request* 入参 path/url 匹配技能端点
            if (tool.startsWith("http_request")) {
                String path = firstNonNull(inputField(input, "path"), inputField(input, "url"));
                if (path != null) {
                    for (String api : fp.apis) {
                        if (apiMatches(path, api)) {
                            add(out, new Evidence("api", normalizePath(path), api, tool, seq));
                        }
                    }
                }
            }
            // Shell 通道：execute 命令各子命令首 token 匹配
            if ("execute".equals(tool)) {
                String cmd = inputField(input, "command");
                if (cmd != null) {
                    for (String token : commandTokens(cmd)) {
                        for (String sc : fp.shells) {
                            if (tokenMatches(token, sc)) {
                                add(out, new Evidence("shell", token, sc, tool, seq));
                            }
                        }
                    }
                }
            }
            // 脚本通道：任意工具入参引用 scripts/ 下文件（相对路径或文件名）
            for (String rel : fp.scripts) {
                String file = rel.substring(rel.lastIndexOf('/') + 1);
                if (input.contains(rel) || (!file.isEmpty() && input.contains(file))) {
                    add(out, new Evidence("script", rel, rel, tool, seq));
                }
            }
        }
    }

    private static void add(Map<String, Evidence> out, Evidence e) {
        Evidence prev = out.get(e.key());
        if (prev == null) {
            out.put(e.key(), e);
        } else {
            prev.count++;
        }
    }

    // ============================== 匹配规则 ==============================

    /** 端点匹配：精确相等，或技能端点中 {占位符} 段视作通配、其余段逐一相等 */
    static boolean apiMatches(String sessionPath, String skillApi) {
        String p = normalizePath(sessionPath);
        String s = normalizePath(skillApi);
        if (p.equals(s)) {
            return true;
        }
        String[] ps = p.split("/");
        String[] ss = s.split("/");
        if (ps.length != ss.length) {
            return false;
        }
        for (int i = 0; i < ss.length; i++) {
            if (ss[i].startsWith("{") && ss[i].endsWith("}") && ss[i].length() > 2) {
                continue;
            }
            if (!ss[i].equals(ps[i])) {
                return false;
            }
        }
        return true;
    }

    /** 去查询串与尾部斜杠 */
    static String normalizePath(String s) {
        String t = s.trim();
        int q = t.indexOf('?');
        if (q >= 0) {
            t = t.substring(0, q);
        }
        while (t.endsWith("/") && t.length() > 1) {
            t = t.substring(0, t.length() - 1);
        }
        return t;
    }

    /** 复合命令（&& ; | 分隔）各子命令的首 token，去引号 */
    static List<String> commandTokens(String command) {
        List<String> tokens = new ArrayList<>();
        for (String sub : CMD_SPLIT.split(command)) {
            String s = sub.trim();
            if (s.isEmpty()) {
                continue;
            }
            String head = s.split("\\s+", 2)[0];
            if (head.length() >= 2 && head.startsWith("\"") && head.endsWith("\"")) {
                head = head.substring(1, head.length() - 1);
            }
            if (!head.isEmpty()) {
                tokens.add(head);
            }
        }
        return tokens;
    }

    /** 命令 token 匹配：忽略大小写；python3 ≈ python（检测侧与模型侧常不一致） */
    static boolean tokenMatches(String token, String skillCmd) {
        return normToken(token).equals(normToken(skillCmd));
    }

    private static String normToken(String s) {
        return "python3".equalsIgnoreCase(s) ? "python" : s.toLowerCase();
    }

    private static boolean containsIgnoreCase(String haystack, String needle) {
        return haystack.toLowerCase().contains(needle.toLowerCase());
    }

    // ============================== 工具入参解析 ==============================

    /** 解析工具入参 JSON 的字符串字段；非 JSON / 字段缺失返回 null（flat 截断消息降级） */
    private String inputField(String inputJson, String field) {
        JsonNode v = inputNode(inputJson);
        if (v != null) {
            JsonNode f = v.get(field);
            if (f != null && f.isTextual()) {
                return f.asText();
            }
        }
        return null;
    }

    private JsonNode inputNode(String inputJson) {
        try {
            return mapper.readTree(inputJson);
        } catch (Exception e) {
            return null;
        }
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }

    private static String firstGroup(Pattern p, String text) {
        Matcher m = p.matcher(text);
        return m.find() ? m.group(1).trim() : null;
    }

    private static String str(Object o) {
        return o instanceof String s ? s : null;
    }

    // ============================== 内部结构 ==============================

    /** 同名技能的加载归并：首次加载所在步骤序 + 加载次数 */
    private static final class SkillLoads {
        final String id;
        final String desc;
        final int seq;
        int times = 1;

        SkillLoads(String id, String desc, int seq) {
            this.id = id;
            this.desc = desc;
            this.seq = seq;
        }
    }

    /** 判定用技能指纹（已剔除 hidden* 忽略项） */
    private record Fingerprints(List<String> apis, List<String> shells,
            List<String> scripts, List<String> keywords) {

        static Fingerprints of(SkillAnalysis sa) {
            List<String> hiddenApis = nz(sa.hiddenApis());
            List<String> hiddenCmds = nz(sa.hiddenShellCommands());
            List<String> apis = new ArrayList<>();
            for (String a : nz(sa.apis())) {
                if (!hiddenApis.contains(a)) {
                    apis.add(a);
                }
            }
            List<String> shells = new ArrayList<>();
            if (sa.shellCommands() != null) {
                for (SkillAnalysis.ShellCmd c : sa.shellCommands()) {
                    if (c.command() != null && !hiddenCmds.contains(c.command())) {
                        shells.add(c.command());
                    }
                }
            }
            List<String> scripts = new ArrayList<>();
            for (String f : nz(sa.scannedFiles())) {
                if (f.startsWith("scripts/")) {
                    scripts.add(f);
                }
            }
            return new Fingerprints(apis, shells, scripts, nz(sa.keyInfo()));
        }

        boolean isEmpty() {
            return apis.isEmpty() && shells.isEmpty() && scripts.isEmpty() && keywords.isEmpty();
        }

        private static List<String> nz(List<String> l) {
            return l == null ? List.of() : l;
        }
    }

    /** 单条证据：key 用于去重（同通道同命中同工具合并计数） */
    private static final class Evidence {
        final String type;
        final String match;
        final String fingerprint;
        final String tool;
        final int step;
        int count = 1;

        Evidence(String type, String match, String fingerprint, String tool, int step) {
            this.type = type;
            this.match = match;
            this.fingerprint = fingerprint;
            this.tool = tool;
            this.step = step;
        }

        String key() {
            return type + "|" + match + "|" + fingerprint + "|" + tool;
        }
    }
}
