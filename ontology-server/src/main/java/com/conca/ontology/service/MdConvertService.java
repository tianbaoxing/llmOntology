package com.conca.ontology.service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.ToXMLContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.conca.ontology.model.FileEntry;
import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter;

/**
 * 二进制文件（pdf/doc/docx/xls/xlsx）转 Markdown：
 * Tika 解析为 XHTML（标题/表格/列表结构保留）→ 截取 body → flexmark 转 GFM Markdown。
 * MD 命名 <原文件全名>.md，落 app.md-dir（默认 files-md/，镜像 upload-dir 相对路径）；
 * 空结果检测：正文有效字符数 < convert-min-chars 判 FAILED（典型场景：扫描件 PDF 无文本层）。
 */
@Service
public class MdConvertService {

    private static final Logger log = LoggerFactory.getLogger(MdConvertService.class);
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** 剥离 MD 语法符号与空白后剩余的"有效字符"（字母/数字/汉字等） */
    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");

    @Value("${app.md-dir:files-md}")
    private String mdDir;

    @Value("${app.convert-ext:pdf,doc,docx,xls,xlsx}")
    private String convertExt;

    @Value("${app.convert-min-chars:50}")
    private int convertMinChars;

    /** 转换结果：状态 + 失败原因（成功时 content 已写入 MD 文件） */
    public record ConvertResult(String status, String msg) {
        static ConvertResult ok() { return new ConvertResult(FileEntry.STATUS_SUCCESS, null); }
        static ConvertResult fail(String msg) { return new ConvertResult(FileEntry.STATUS_FAILED, msg); }
    }

    /** 是否属于需转换的二进制扩展名 */
    public boolean shouldConvert(String ext) {
        return ext != null && convertExts().contains(ext.toLowerCase(Locale.ROOT));
    }

    /** MD 文件名：<原文件全名>.md（含相对路径，如 sub/a.pdf → sub/a.pdf.md） */
    public String mdNameOf(String name) {
        return name + ".md";
    }

    public Path mdDir() {
        return Path.of(mdDir).toAbsolutePath().normalize();
    }

    /** MD 绝对路径（入参为原文件相对名或 mdName 均可，均基于 md 目录解析） */
    public Path mdPathOf(String name) {
        return mdDir().resolve(name).normalize();
    }

    /** 执行转换并把结果原子写入 MD 路径；失败不产出 MD 文件 */
    public ConvertResult convertAndWrite(Path source, String relName) {
        try {
            String md = doConvert(source, relName);
            if (effectiveChars(md) < convertMinChars) {
                return ConvertResult.fail("转换后无有效文本内容（疑似扫描件或空文档，无文本层）");
            }
            Path target = mdPathOf(mdNameOf(relName));
            Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.write(tmp, md.getBytes(StandardCharsets.UTF_8));
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            log.info("file converted: {} -> {}", relName, target);
            return ConvertResult.ok();
        } catch (Exception | LinkageError e) {
            // LinkageError 兜底：依赖缺失（NoClassDefFoundError）等 Error 也不能炸上传，
            // 统一记 FAILED + 原因（页面可见、可重试），原件保留
            log.warn("file convert failed: {} ({})", relName, e.toString());
            return ConvertResult.fail(e.getMessage() == null ? e.getClass().getName() : e.getMessage());
        }
    }

    /** MD 文件的修改时间（对账回填 convertedAt 用） */
    public String mtimeOf(Path mdPath) {
        try {
            return LocalDateTime.ofInstant(Files.getLastModifiedTime(mdPath).toInstant(),
                    ZoneId.systemDefault()).format(TIME_FMT);
        } catch (Exception e) {
            return LocalDateTime.now().format(TIME_FMT);
        }
    }

    // ---------------- 内部 ----------------

    private String doConvert(Path source, String relName) throws Exception {
        // 1. Tika → XHTML（AutoDetectParser 按内容嗅探，白名单已限制上传类型）
        AutoDetectParser parser = new AutoDetectParser();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ToXMLContentHandler handler = new ToXMLContentHandler(out, StandardCharsets.UTF_8.name());
        try (InputStream is = Files.newInputStream(source)) {
            parser.parse(is, handler, new Metadata(), new ParseContext());
        }
        String xhtml = out.toString(StandardCharsets.UTF_8);
        // 2. 只取 body（去掉 head 的 meta/title 噪音）
        String html = extractBody(xhtml);
        // 3. PDF 每页的 <div class="page"> 换成分隔线，便于 LLM 定位引用
        html = html.replaceAll("(?i)<div\\s+class=\"page\">", "<hr/>");
        // 4. flexmark → GFM Markdown（表格转管道表格）
        String md = FlexmarkHtmlConverter.builder().build().convert(html).trim();
        // 5. 头部插入来源注释，便于 LLM 溯源
        return "> 来源: " + relName + " · 转换于 " + LocalDateTime.now().format(TIME_FMT) + "\n\n" + md + "\n";
    }

    private static String extractBody(String xhtml) {
        int b = xhtml.indexOf("<body");
        int e = xhtml.lastIndexOf("</body>");
        if (b < 0 || e <= b) {
            return xhtml;
        }
        int gt = xhtml.indexOf('>', b);
        return gt >= 0 && gt < e ? xhtml.substring(gt + 1, e) : xhtml;
    }

    /** 剥离 MD 语法符号与空白后的有效字符数（空结果检测） */
    private static int effectiveChars(String md) {
        return NON_WORD.matcher(md).replaceAll("").length();
    }

    private Set<String> convertExts() {
        return Set.of(convertExt.split(","));
    }
}
