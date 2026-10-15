package com.conca.ontology.eval.graders.rule.match;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

import org.springframework.stereotype.Component;

import com.conca.ontology.eval.Grader;
import com.conca.ontology.eval.model.GraderInput;
import com.conca.ontology.eval.model.GraderScore;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 语法检查评测器（规则类，对齐 OpenJudge SyntaxChecker）：
 * 支持多语言（java/json/python/js/ts/sql/xml/yaml/通用代码）。
 * <ul>
 *   <li>JAVA：调用 JDK 内置 javax.tools.JavaCompiler（无 JDK 时回落启发式）。
 *       纯代码片段会被包装进一个 dummy public class Dummy { void m() { ... } }。
 *       完整 .java 文件直接编译。</li>
 *   <li>JSON：Jackson 解析（与 format_json 同一通道，但本评测器额外报告错误详情行号）。</li>
 *   <li>Python：缩进一致性 + 括号/冒号/关键字匹配（启发式，不依赖外部解释器）。</li>
 *   <li>其他：括号/引号/注释匹配的通用平衡校验 + 关键字规则。</li>
 * </ul>
 * 数据源优先级：1) params.code（显式代码块，推荐） 2) response（剥离代码围栏后）。
 * 语言判定顺序：1) params.language（java/json/py/py3/python/js/ts/javascript/typescript/sql/xml/yaml/generic）
 * 2) 围栏标记 ```java 等 3) 内容启发式。
 *
 * @author tianbaoxing
 * @date 2026-09-20
 */
@Component
public class SyntaxCheckerGrader implements Grader {

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    @Override
    public String name() {
        return "syntax_checker";
    }

    @Override
    public String description() {
        return "语法检查（规则类，内置多语言解析器）：支持 Java（JDK 编译）/JSON（Jackson）/Python/JS/TS/SQL/XML/YAML/"
                + "通用代码的括号平衡与关键字检查；取 params.code（推荐）或 response 剥离围栏后，按 params.language 或围栏标记自动识别语言";
    }

    @Override
    public GraderScore evaluate(GraderInput input) {
        if (input.record().failed()) {
            return GraderScore.fail(name(), "agent 调用失败：" + input.record().error());
        }
        // ---- 1. 取代码片段与语言标记（直接读 params，不新增 GraderInput 一等访问器） ----
        Object cv = input.params().get("code");
        Object lv = input.params().get("language");
        String rawCode = cv == null ? null : String.valueOf(cv);
        String language = lv == null ? null : String.valueOf(lv);
        String response = input.response() == null ? "" : input.response();
        if (rawCode == null || rawCode.isBlank()) {
            Fenced fc = extractFenced(response);
            rawCode = fc.code;
            if ((language == null || language.isBlank()) && fc.language != null) {
                language = fc.language;
            }
        }
        if (rawCode == null || rawCode.isBlank()) {
            return GraderScore.of(name(), 0.0, "没有可分析的代码片段（params.code 为空且 response 中未检测到代码围栏）", Map.of());
        }
        if (language == null || language.isBlank()) {
            language = detectLanguage(rawCode);
        }
        String lang = language.toLowerCase(Locale.ROOT);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("language", lang);
        meta.put("code_length", rawCode.length());
        meta.put("lines", rawCode.split("\n", -1).length);

        // ---- 2. 按语言分派 ----
        return switch (lang) {
            case "java" -> checkJava(rawCode, meta);
            case "json" -> checkJson(rawCode, meta);
            case "py", "python", "python3" -> checkPython(rawCode, meta);
            case "js", "javascript", "ts", "typescript" -> checkJsTs(rawCode, lang, meta);
            case "sql" -> checkSql(rawCode, meta);
            case "xml", "html" -> checkXmlHtml(rawCode, lang, meta);
            case "yaml", "yml" -> checkYaml(rawCode, meta);
            default -> checkGeneric(rawCode, lang, meta);
        };
    }

    // ============================================================
    // Java：javax.tools 编译（需 JDK，无 JDK 回落平衡校验）
    // ============================================================
    private GraderScore checkJava(String raw, Map<String, Object> meta) {
        String source = raw;
        boolean wrapped = false;
        if (!raw.contains("class ") && !raw.contains("interface ") && !raw.contains("enum ")) {
            source = "public class Dummy { void m() {\n" + raw + "\n} }";
            wrapped = true;
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            meta.put("warning", "当前环境为 JRE（无 JDK 编译器 javax.tools），回落通用平衡校验");
            return checkGeneric(raw, "java(fallback)", meta);
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        String className = "Dummy";
        JavaMemFile src = new JavaMemFile(className, source);
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        Boolean ok;
        try (Writer err = new OutputStreamWriter(errBytes, StandardCharsets.UTF_8)) {
            JavaCompiler.CompilationTask task = compiler.getTask(
                    err, null, diagnostics, List.of("-proc:none", "-Xlint:none"),
                    null, List.of(src));
            ok = task.call();
        } catch (Exception e) {
            meta.put("compiler_exception", e.getMessage());
            return checkGeneric(raw, "java(fallback-on-exception)", meta);
        }
        meta.put("wrapped_in_dummy_class", wrapped);
        if (Boolean.TRUE.equals(ok)) {
            return GraderScore.of(name(), 1.0, "Java 语法正确（编译通过" + (wrapped ? "，代码片段自动包裹 Dummy 类" : "") + "）", meta);
        }
        List<Map<String, Object>> errors = new ArrayList<>();
        for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
            if (d.getKind() == Diagnostic.Kind.ERROR) {
                long line = wrapped ? Math.max(1, d.getLineNumber() - 1) : d.getLineNumber();
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("line", line);
                e.put("column", d.getColumnNumber());
                String msg = d.getMessage(Locale.getDefault());
                if (wrapped) msg = msg.replace("Dummy", "(snippet wrapper)");
                e.put("message", msg);
                errors.add(e);
            }
        }
        meta.put("errors", errors);
        meta.put("error_count", errors.size());
        String stderr = errBytes.toString(StandardCharsets.UTF_8);
        if (!stderr.isBlank()) meta.put("stderr", truncate(stderr, 500));
        return GraderScore.of(name(), 0.0,
                "Java 存在语法错误（" + errors.size() + " 处，第 1 行错："
                        + (errors.isEmpty() ? "(无明细)" : errors.get(0).get("message")) + "）", meta);
    }

    private static final class JavaMemFile extends SimpleJavaFileObject {
        private final String code;
        JavaMemFile(String name, String code) {
            super(URI.create("string:///" + name.replace('.', '/') + Kind.SOURCE.extension), Kind.SOURCE);
            this.code = code;
        }
        @Override public CharSequence getCharContent(boolean ignore) { return code; }
    }

    // ============================================================
    // JSON：Jackson 解析（复用 FormatJsonGrader.stripFence 逻辑 + 错误详情）
    // ============================================================
    private GraderScore checkJson(String raw, Map<String, Object> meta) {
        String s = FormatJsonGrader.stripFence(raw.trim());
        try {
            JSON_MAPPER.readTree(s);
            return GraderScore.of(name(), 1.0, "JSON 语法正确", meta);
        } catch (Exception e) {
            String msg = e.getMessage() == null ? "解析失败" : e.getMessage();
            Integer line = null;
            Integer col = null;
            Matcher lm = Pattern.compile("\\(code (\\w+\\S*):?\\s*(\\d+)(?::(\\d+))?\\)").matcher(msg);
            if (lm.find()) {
                try { line = Integer.parseInt(lm.group(2)); } catch (Exception ignore) {}
                if (lm.group(3) != null) try { col = Integer.parseInt(lm.group(3)); } catch (Exception ignore) {}
            }
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("message", truncate(msg, 500));
            if (line != null) err.put("line", line);
            if (col != null) err.put("column", col);
            meta.put("error", err);
            return GraderScore.of(name(), 0.0, "JSON 语法错误：" + truncate(msg, 200), meta);
        }
    }

    // ============================================================
    // Python：缩进 + 括号/冒号/关键字启发式
    // ============================================================
    private GraderScore checkPython(String raw, Map<String, Object> meta) {
        String code = FormatJsonGrader.stripFence(raw.trim());
        String[] lines = code.split("\n", -1);
        List<String> errors = new ArrayList<>();
        BalanceState bal = new BalanceState();
        List<Integer> indentStack = new ArrayList<>(List.of(0));
        boolean inTripleSingle = false;
        boolean inTripleDouble = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int lineNo = i + 1;
            String stripped = line;
            // 三引号跨行状态（简单翻转）
            stripped = skipPyStringsAndComments(stripped, new boolean[]{inTripleSingle, inTripleDouble});
            inTripleSingle = bal._ts; inTripleDouble = bal._td;
            if (stripped.isBlank()) {
                // 空行不影响缩进
                bal.update(line);
                continue;
            }
            // 行首缩进校验（仅对非空行）
            int indent = leadingSpaces(line);
            if (indent % 2 != 0 && indent != indentStack.get(indentStack.size() - 1)) {
                errors.add("第" + lineNo + "行：缩进为奇数且与上级不相等（建议 4/2/8 空格或 tab 统一）");
            }
            String body = stripped.stripTrailing();
            // 冒号检查：if/for/while/def/class/else/elif/try/except/finally/with/... 行尾需冒号
            if (endsWithColonHeader(body)) {
                if (!body.endsWith(":")) {
                    errors.add("第" + lineNo + "行：块控制语句末尾缺少冒号 `:`");
                }
            }
            // 无意义冒号（非控制语句、非切片、非字典）例如 `x = 1 :`
            if (body.endsWith(":") && !endsWithColonHeader(body) && !isLikelySliceOrDict(body)) {
                errors.add("第" + lineNo + "行：行尾多余的冒号");
            }
            // 缩进层级：上一行以冒号结尾，则下一行缩进必须增加
            if (i > 0) {
                String prev = skipPyStringsAndComments(lines[i - 1].stripTrailing(), new boolean[]{inTripleSingle, inTripleDouble});
                if (prev.endsWith(":")) {
                    if (indent <= indentStack.get(indentStack.size() - 1)) {
                        errors.add("第" + lineNo + "行：前一行以冒号结束（代码块开始），但本行缩进未增加");
                    } else {
                        indentStack.add(indent);
                    }
                } else if (indent < indentStack.get(indentStack.size() - 1)) {
                    // 退出嵌套：回退 indentStack 到最近一个 == indent 的位置
                    while (indentStack.size() > 1 && indent < indentStack.get(indentStack.size() - 1)) {
                        indentStack.remove(indentStack.size() - 1);
                    }
                    if (indent != indentStack.get(indentStack.size() - 1)) {
                        errors.add("第" + lineNo + "行：缩进层级未对齐（回退后无法匹配任意外层）");
                    }
                }
            }
            bal.update(line);
        }
        // 括号平衡
        if (bal.paren != 0) errors.add("括号 `()` 未平衡：剩余 " + (bal.paren > 0 ? "未闭合" : "多余闭合") + " " + Math.abs(bal.paren) + " 个");
        if (bal.square != 0) errors.add("方括号 `[]` 未平衡：剩余 " + (bal.square > 0 ? "未闭合" : "多余闭合") + " " + Math.abs(bal.square) + " 个");
        if (bal.curly != 0) errors.add("花括号 `{}` 未平衡：剩余 " + (bal.curly > 0 ? "未闭合" : "多余闭合") + " " + Math.abs(bal.curly) + " 个");
        if (!errors.isEmpty()) {
            meta.put("errors", errors);
            return GraderScore.of(name(), 0.0, "Python 语法检查未通过（" + errors.size() + " 处：" + errors.get(0) + "…）", meta);
        }
        return GraderScore.of(name(), 1.0, "Python 启发式语法检查通过", meta);
    }

    private static int leadingSpaces(String s) {
        int n = 0;
        while (n < s.length() && Character.isWhitespace(s.charAt(n))) n++;
        return n;
    }

    private static final List<String> PY_BLOCK_KEYWORDS = Arrays.asList(
            "if", "elif", "else", "for", "while", "def", "class",
            "try", "except", "finally", "with", "match", "case");

    private static boolean endsWithColonHeader(String body) {
        String t = body.strip();
        for (String kw : PY_BLOCK_KEYWORDS) {
            if (t.startsWith(kw + " ") || t.equals(kw + ":") || t.startsWith(kw + "\t")) return true;
        }
        // lambda 赋值或单行 try/except 简化
        if (t.startsWith("@")) return true; // 装饰器后可能有 def/class
        return false;
    }

    private static boolean isLikelySliceOrDict(String body) {
        // a[1:2] 切片, {"a": 1} 字典值, lambda x: x+1 等合法冒号
        return body.contains("[") || body.contains("{") || body.contains("lambda");
    }

    /** 简单去除 Python 字符串和注释内容（只用于关键字/冒号判断；不能完全替代 AST） */
    private static String skipPyStringsAndComments(String line, boolean[] triState) {
        boolean ts = triState[0];
        boolean td = triState[1];
        StringBuilder out = new StringBuilder();
        int i = 0;
        char[] a = line.toCharArray();
        while (i < a.length) {
            char c = a[i];
            char c2 = i + 1 < a.length ? a[i + 1] : '\0';
            char c3 = i + 2 < a.length ? a[i + 2] : '\0';
            if (td) {
                if (c == '"' && c2 == '"' && c3 == '"') { td = false; i += 3; continue; }
                i++; continue;
            }
            if (ts) {
                if (c == '\'' && c2 == '\'' && c3 == '\'') { ts = false; i += 3; continue; }
                i++; continue;
            }
            if (c == '"' && c2 == '"' && c3 == '"') { td = true; i += 3; continue; }
            if (c == '\'' && c2 == '\'' && c3 == '\'') { ts = true; i += 3; continue; }
            if (c == '#') { break; }
            if (c == '"' || c == '\'') {
                char q = c; i++;
                while (i < a.length) {
                    if (a[i] == '\\' && i + 1 < a.length) { i += 2; continue; }
                    if (a[i] == q) { i++; break; }
                    i++;
                }
                continue;
            }
            out.append(c);
            i++;
        }
        triState[0] = ts;
        triState[1] = td;
        return out.toString();
    }

    // ============================================================
    // JS / TS：通用括号 + 关键字粗检 + 分号风险提示
    // ============================================================
    private GraderScore checkJsTs(String raw, String lang, Map<String, Object> meta) {
        String code = FormatJsonGrader.stripFence(raw.trim());
        BalanceState bal = new BalanceState();
        List<String> errors = new ArrayList<>();
        boolean inLine = false, inBlock = false;
        boolean inSq = false, inDq = false, inBt = false;
        char[] a = code.toCharArray();
        for (int i = 0; i < a.length; i++) {
            char c = a[i];
            char n = i + 1 < a.length ? a[i + 1] : '\0';
            if (inLine) { if (c == '\n') inLine = false; else continue; }
            if (inBlock) { if (c == '*' && n == '/') { inBlock = false; i++; } continue; }
            if (inSq) {
                if (c == '\\') { i++; continue; }
                if (c == '\'') inSq = false;
                continue;
            }
            if (inDq) {
                if (c == '\\') { i++; continue; }
                if (c == '"') inDq = false;
                continue;
            }
            if (inBt) {
                if (c == '\\') { i++; continue; }
                if (c == '`') inBt = false;
                continue;
            }
            if (c == '/' && n == '/') { inLine = true; i++; continue; }
            if (c == '/' && n == '*') { inBlock = true; i++; continue; }
            if (c == '\'') { inSq = true; continue; }
            if (c == '"')  { inDq = true; continue; }
            if (c == '`')  { inBt = true; continue; }
            bal.ch(c);
        }
        if (bal.paren != 0) errors.add("括号 `()` 未平衡 (" + bal.paren + ")");
        if (bal.square != 0) errors.add("方括号 `[]` 未平衡 (" + bal.square + ")");
        if (bal.curly != 0) errors.add("花括号 `{}` 未平衡 (" + bal.curly + ")");
        if (inSq || inDq || inBt) errors.add("字符串/模板字面量未闭合");
        if (inBlock) errors.add("块注释 `/* */` 未闭合");
        // 关键字粗匹配（启发式）：function / if / for / while / catch 等必须跟 () {} 对应
        detectUnmatchedKeywordBlock(code, "function", errors);
        detectUnmatchedKeywordBlock(code, "if", errors);
        detectUnmatchedKeywordBlock(code, "for", errors);
        detectUnmatchedKeywordBlock(code, "while", errors);
        detectUnmatchedKeywordBlock(code, "catch", errors);
        detectUnmatchedKeywordBlock(code, "switch", errors);
        detectUnmatchedKeywordBlock(code, "class", errors);
        if (!errors.isEmpty()) {
            meta.put("errors", errors);
            return GraderScore.of(name(), 0.0, lang.toUpperCase(Locale.ROOT) + " 语法检查未通过（" + errors.size() + " 处）", meta);
        }
        return GraderScore.of(name(), 1.0, lang.toUpperCase(Locale.ROOT) + " 启发式语法检查通过", meta);
    }

    private static void detectUnmatchedKeywordBlock(String code, String kw, List<String> errors) {
        Pattern p = Pattern.compile("\\b" + Pattern.quote(kw) + "\\b", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(code);
        while (m.find()) {
            int start = m.end();
            // 往后找最近的 `{`
            int brace = code.indexOf('{', start);
            int closeStmt = code.indexOf(';', start);
            if (brace < 0 || (closeStmt >= 0 && closeStmt < brace)) {
                // 可能是 `if (x) return;` 单行（无花括号），简单放行
                // 这里粗略地仅在 `)` 和 `;` 之间无语句时才不报错；简化为不报错
                continue;
            }
        }
    }

    // ============================================================
    // SQL：括号 + 关键字 + 结尾分号提示
    // ============================================================
    private GraderScore checkSql(String raw, Map<String, Object> meta) {
        String code = FormatJsonGrader.stripFence(raw.trim());
        BalanceState bal = new BalanceState();
        List<String> errors = new ArrayList<>();
        boolean inSq = false, inDq = false, inLine = false;
        char[] a = code.toCharArray();
        for (int i = 0; i < a.length; i++) {
            char c = a[i];
            if (inLine) { if (c == '\n') inLine = false; continue; }
            if (inSq) { if (c == '\'') inSq = false; else if (c == '\\') i++; continue; }
            if (inDq) { if (c == '"') inDq = false; else if (c == '\\') i++; continue; }
            if (c == '-' && i + 1 < a.length && a[i + 1] == '-') { inLine = true; i++; continue; }
            if (c == '\'') inSq = true;
            else if (c == '"') inDq = true;
            else bal.ch(c);
        }
        if (bal.paren != 0) errors.add("括号 () 未平衡 (" + bal.paren + ")");
        String up = code.toUpperCase(Locale.ROOT);
        // 粗检 SELECT/FROM/WHERE/GROUP BY/ORDER BY 常见配对
        if (up.contains("SELECT") && !up.contains("FROM")) errors.add("含 SELECT 但未找到 FROM");
        if (up.contains("GROUP BY") && !up.contains("SELECT")) errors.add("含 GROUP BY 但未找到 SELECT");
        if (up.contains("ORDER BY") && !up.contains("SELECT")) errors.add("含 ORDER BY 但未找到 SELECT");
        if (!errors.isEmpty()) {
            meta.put("errors", errors);
            return GraderScore.of(name(), 0.0, "SQL 语法检查未通过（" + errors.size() + " 处）", meta);
        }
        return GraderScore.of(name(), 1.0, "SQL 启发式语法检查通过", meta);
    }

    // ============================================================
    // XML / HTML：标签匹配 + 注释
    // ============================================================
    private GraderScore checkXmlHtml(String raw, String lang, Map<String, Object> meta) {
        String code = FormatJsonGrader.stripFence(raw.trim());
        List<String> errors = new ArrayList<>();
        List<String> stack = new ArrayList<>();
        boolean inComment = false;
        boolean inDoctype = false;
        Pattern openTag = Pattern.compile("<\\s*([A-Za-z_][A-Za-z0-9_\\-\\.]*)");
        Pattern closeTag = Pattern.compile("</\\s*([A-Za-z_][A-Za-z0-9_\\-\\.]*)\\s*>");
        Pattern selfClose = Pattern.compile("/\\s*>$");
        Matcher m = Pattern.compile("<[^>]+>|<!--|-->|<!DOCTYPE[^>]*>").matcher(code);
        while (m.find()) {
            String tok = m.group();
            if (inComment) {
                if (tok.equals("-->")) inComment = false;
                continue;
            }
            if (tok.startsWith("<!--")) { inComment = true; continue; }
            if (tok.startsWith("<!DOCTYPE")) { inDoctype = true; continue; }
            if (tok.startsWith("</")) {
                Matcher cm = closeTag.matcher(tok);
                if (cm.find()) {
                    String name = cm.group(1).toLowerCase(Locale.ROOT);
                    if (!Collections.disjoint(stack, List.of(name))) {
                        while (!stack.isEmpty() && !stack.get(stack.size() - 1).equalsIgnoreCase(name)) {
                            String popped = stack.remove(stack.size() - 1);
                            if (!isVoidHtml(popped)) errors.add("标签未闭合：<" + popped + ">");
                        }
                        if (!stack.isEmpty()) stack.remove(stack.size() - 1);
                    } else {
                        errors.add("多余闭合标签：</" + name + ">");
                    }
                }
                continue;
            }
            if (tok.startsWith("<") && !tok.startsWith("</")) {
                Matcher om = openTag.matcher(tok);
                if (om.find()) {
                    String name = om.group(1);
                    if (!selfClose.matcher(tok).find() && !isVoidHtml(name.toLowerCase(Locale.ROOT))) {
                        stack.add(name.toLowerCase(Locale.ROOT));
                    }
                }
            }
        }
        for (String left : stack) {
            if (!isVoidHtml(left)) errors.add("标签未闭合：<" + left + ">");
        }
        if (inComment) errors.add("XML/HTML 注释 <!-- --> 未闭合");
        if (!errors.isEmpty()) {
            meta.put("errors", errors);
            return GraderScore.of(name(), 0.0, lang.toUpperCase(Locale.ROOT) + " 语法检查未通过（" + errors.size() + " 处）", meta);
        }
        return GraderScore.of(name(), 1.0, lang.toUpperCase(Locale.ROOT) + " 标签匹配检查通过", meta);
    }

    private static final java.util.Set<String> VOID_HTML = new java.util.HashSet<>(Arrays.asList(
            "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr"));
    private static boolean isVoidHtml(String n) { return VOID_HTML.contains(n); }

    // ============================================================
    // YAML：缩进粗检 + Tab 警告 + 冒号空格
    // ============================================================
    private GraderScore checkYaml(String raw, Map<String, Object> meta) {
        String code = FormatJsonGrader.stripFence(raw.trim());
        String[] lines = code.split("\n", -1);
        List<String> errors = new ArrayList<>();
        int lastIndent = -1;
        List<Integer> indentStack = new ArrayList<>(List.of(-1));
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String stripped = line.stripTrailing();
            if (stripped.isBlank() || stripped.startsWith("#")) continue;
            if (line.indexOf('\t') >= 0) errors.add("第" + (i + 1) + "行：检测到 tab 字符（YAML 禁用 tab）");
            int indent = leadingSpaces(line);
            if (indent > lastIndent) {
                indentStack.add(indent);
            } else if (indent < lastIndent) {
                while (indentStack.size() > 1 && indent < indentStack.get(indentStack.size() - 1)) {
                    indentStack.remove(indentStack.size() - 1);
                }
                if (indent != indentStack.get(indentStack.size() - 1)) {
                    errors.add("第" + (i + 1) + "行：缩进回退未对齐任意外层");
                }
            }
            lastIndent = indent;
            // 冒号后至少一个空格（除非在行尾 `key:` 空值）
            String body = stripped.replaceFirst("\\s+#.*$", "");
            for (int j = 0; j < body.length(); j++) {
                if (body.charAt(j) == ':' && j + 1 < body.length()) {
                    char next = body.charAt(j + 1);
                    if (next != ' ' && next != '\t') {
                        // 允许 URL 的 https:// 与时间 10:30
                        boolean safe = (j > 0 && (body.charAt(j - 1) == 'p' || body.charAt(j - 1) == 's')
                                && j + 2 < body.length() && body.charAt(j + 1) == '/' && body.charAt(j + 2) == '/')
                                || (j > 0 && Character.isDigit(body.charAt(j - 1))
                                        && j + 1 < body.length() && Character.isDigit(body.charAt(j + 1)));
                        if (!safe) {
                            errors.add("第" + (i + 1) + "行：冒号后缺少空格（YAML 要求 `key: value`）");
                            break;
                        }
                    }
                }
            }
        }
        if (!errors.isEmpty()) {
            meta.put("errors", errors);
            return GraderScore.of(name(), 0.0, "YAML 语法检查未通过（" + errors.size() + " 处）", meta);
        }
        return GraderScore.of(name(), 1.0, "YAML 启发式语法检查通过", meta);
    }

    // ============================================================
    // 通用：括号/引号平衡
    // ============================================================
    private GraderScore checkGeneric(String raw, String lang, Map<String, Object> meta) {
        String code = FormatJsonGrader.stripFence(raw.trim());
        BalanceState bal = new BalanceState();
        bal.update(code);
        List<String> errors = new ArrayList<>();
        if (bal.paren != 0) errors.add("括号 () 未平衡 (" + bal.paren + ")");
        if (bal.square != 0) errors.add("方括号 [] 未平衡 (" + bal.square + ")");
        if (bal.curly != 0) errors.add("花括号 {} 未平衡 (" + bal.curly + ")");
        if (bal.quoteSingle % 2 != 0) errors.add("单引号未闭合");
        if (bal.quoteDouble % 2 != 0) errors.add("双引号未闭合");
        if (!errors.isEmpty()) {
            meta.put("errors", errors);
            return GraderScore.of(name(), 0.0, "通用语法检查未通过（语言=" + lang + "，" + errors.size() + " 处）", meta);
        }
        return GraderScore.of(name(), 1.0, "通用语法检查通过（括号/引号平衡，语言=" + lang + "）", meta);
    }

    // ============================================================
    // 辅助：括号/引号平衡状态
    // ============================================================
    private static final class BalanceState {
        int paren, square, curly;
        int quoteSingle, quoteDouble;
        boolean _ts, _td;
        void ch(char c) {
            switch (c) {
                case '(' -> paren++;
                case ')' -> paren--;
                case '[' -> square++;
                case ']' -> square--;
                case '{' -> curly++;
                case '}' -> curly--;
                case '\'' -> quoteSingle++;
                case '"'  -> quoteDouble++;
                default -> {}
            }
        }
        void update(String line) {
            for (int i = 0; i < line.length(); i++) ch(line.charAt(i));
        }
    }

    private static final Pattern LANG_FENCE = Pattern.compile("^```(\\w+)?\\s*$");

    private record Fenced(String code, String language) {
    }

    private static Fenced extractFenced(String response) {
        if (response == null) return new Fenced("", null);
        String s = response.trim();
        if (!s.contains("```")) return new Fenced(s, null);
        String[] lines = s.split("\n");
        StringBuilder sb = new StringBuilder();
        String lang = null;
        boolean in = false;
        for (String line : lines) {
            Matcher fm = LANG_FENCE.matcher(line.trim());
            if (fm.matches()) {
                if (!in) {
                    in = true;
                    if (fm.group(1) != null) lang = fm.group(1);
                } else {
                    in = false;
                }
                continue;
            }
            if (in) sb.append(line).append('\n');
        }
        if (sb.isEmpty()) return new Fenced(s, lang);
        return new Fenced(sb.toString().trim(), lang);
    }

    private static String detectLanguage(String code) {
        String s = code.stripLeading().toLowerCase(Locale.ROOT);
        if (s.startsWith("{") || s.startsWith("[")) return "json";
        if (s.startsWith("<?xml") || s.startsWith("<html") || s.startsWith("<!doctype html")) return "xml";
        if (s.startsWith("package ") || s.contains("public class ") || s.contains("public static void main")) return "java";
        if (s.startsWith("import ") && s.contains("def ") && (s.contains("print(") || s.contains("self."))) return "python";
        if (s.startsWith("def ") || s.contains("print(") && s.contains(":")) return "python";
        if (s.startsWith("const ") || s.startsWith("let ") || s.startsWith("var ")
                || s.contains("function ") || s.contains("=>")) return "javascript";
        if (s.startsWith("SELECT\n") || s.startsWith("select ") || s.contains("from ") && (s.contains("select ") || s.contains("SELECT "))) return "sql";
        if (s.contains(":\n") || s.contains(": ") && !s.contains("{") && s.split("\n")[0].contains(":")) return "yaml";
        return "generic";
    }

    static String truncate(String s, int limit) {
        if (s == null) return "";
        return s.length() <= limit ? s : s.substring(0, limit) + "…";
    }
}
