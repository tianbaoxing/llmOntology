package com.conca.ontology.service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.conca.ontology.exception.ApiException;
import com.conca.ontology.model.ForbiddenWordFile;
import com.conca.ontology.model.content.*;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import jakarta.annotation.PostConstruct;
import net.sourceforge.pinyin4j.PinyinHelper;
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType;
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat;
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType;
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType;
import net.sourceforge.pinyin4j.format.exception.BadHanyuPinyinOutputFormatCombination;

@Service
public class ContentSecurityService {

    private static final Logger log = LoggerFactory.getLogger(ContentSecurityService.class);

    private static final int MAX_MATCHES_PER_RULE = 20;
    private static final int SNIPPET_RADIUS = 30;
    private static final int SNIPPET_MAX = 100;
    private static final int VARIANT_CAP = 4;
    private static final int MAX_BASE64_TOKENS = 4;
    private static final int MAX_BASE64_LEN = 4096;

    private static final HanyuPinyinOutputFormat WITH_TONE_FORMAT;
    private static final HanyuPinyinOutputFormat WITHOUT_TONE_FORMAT;

    private static final Map<Character, Character> HOMOGLYPH_MAP;
    private static final Pattern NEEDS_NORMALIZE_RE;
    private static final Pattern BASE64_TOKEN_RE;
    private static final Pattern CN_MOBILE_RE;
    private static final Pattern CN_IDCARD_RE;
    private static final Pattern CONTROL_CHAR_RE;
    private static final Pattern WHITESPACE_RE;

    static {
        WITH_TONE_FORMAT = new HanyuPinyinOutputFormat();
        WITH_TONE_FORMAT.setCaseType(HanyuPinyinCaseType.LOWERCASE);
        WITH_TONE_FORMAT.setToneType(HanyuPinyinToneType.WITH_TONE_NUMBER);
        WITH_TONE_FORMAT.setVCharType(HanyuPinyinVCharType.WITH_V);

        WITHOUT_TONE_FORMAT = new HanyuPinyinOutputFormat();
        WITHOUT_TONE_FORMAT.setCaseType(HanyuPinyinCaseType.LOWERCASE);
        WITHOUT_TONE_FORMAT.setToneType(HanyuPinyinToneType.WITHOUT_TONE);
        WITHOUT_TONE_FORMAT.setVCharType(HanyuPinyinVCharType.WITH_V);

        HOMOGLYPH_MAP = new HashMap<>();
        HOMOGLYPH_MAP.put('а', 'a');
        HOMOGLYPH_MAP.put('е', 'e');
        HOMOGLYPH_MAP.put('о', 'o');
        HOMOGLYPH_MAP.put('р', 'p');
        HOMOGLYPH_MAP.put('с', 'c');
        HOMOGLYPH_MAP.put('х', 'x');
        HOMOGLYPH_MAP.put('у', 'y');
        HOMOGLYPH_MAP.put('і', 'i');
        HOMOGLYPH_MAP.put('ο', 'o');

        NEEDS_NORMALIZE_RE = Pattern.compile("[\\u200b-\\u200d\\u2060\\ufeff\\uff01-\\uff5eаеорсхуіο]");
        BASE64_TOKEN_RE = Pattern.compile("[A-Za-z0-9+/]{16,}={0,2}");
        CN_MOBILE_RE = Pattern.compile("(?<!\\d)(?:\\+?86[- ]?)?(1[3-9]\\d)(\\d{4})(\\d{4})(?!\\d)");
        CN_IDCARD_RE = Pattern.compile("(?<!\\d)(\\d{4})\\d{10}(\\d{3}[\\dXx])(?!\\d)");
        CONTROL_CHAR_RE = Pattern.compile("[\\u0000-\\u001f\\u007f]");
        WHITESPACE_RE = Pattern.compile("\\s+");
    }

    @Value("${app.content-security.rules-file:data/content-security-rules.json}")
    private String rulesFile;

    @Value("${app.content-security.allowlist-file:data/content-security-allowlist.json}")
    private String allowlistFile;

    @Value("${app.forbidden-words-file:data/forbidden-words.json}")
    private String forbiddenWordsFile;

    @Value("${app.forbidden-words.homophone-enabled:true}")
    private boolean homophoneEnabled;

    @Value("${app.forbidden-words.homophone-tone-match:false}")
    private boolean homophoneToneMatch;

    @Value("${app.content-security.block-threshold:0.8}")
    private double blockThreshold;

    @Value("${app.content-security.review-threshold:0.5}")
    private double reviewThreshold;

    @Value("${app.content-security.review-strategy:block}")
    private String reviewStrategy;

    @Value("${app.content-security.variant-layer-enabled:true}")
    private boolean variantLayerEnabled;

    private final ObjectMapper mapper;

    private transient Map<String, Pattern> compiledPatterns;
    private transient Map<String, Pattern> compiledGlobalPatterns;
    private transient List<ContentSecurityRule> allRules;
    private transient Set<String> allowlistedIds;
    private transient Map<String, String> pinyinCache;
    private transient ForbiddenWordFile forbiddenWordFile;

    public ContentSecurityService(ObjectMapper mapper) {
        this.mapper = mapper;
        this.compiledPatterns = new HashMap<>();
        this.compiledGlobalPatterns = new HashMap<>();
        this.allRules = new ArrayList<>();
        this.allowlistedIds = new HashSet<>();
        this.pinyinCache = new HashMap<>();
    }

    @PostConstruct
    public synchronized void reloadAll() {
        loadRulesInternal();
        loadAllowlistInternal();
        loadForbiddenWordsInternal();
    }

    // ========== 文件装载内部方法 ==========

    private void loadRulesInternal() {
        Path file = Path.of(rulesFile).toAbsolutePath();
        ContentSecurityRuleFile rf;
        if (Files.notExists(file)) {
            rf = new ContentSecurityRuleFile(0, List.of());
        } else {
            try {
                rf = mapper.readValue(file.toFile(), ContentSecurityRuleFile.class);
            } catch (Exception e) {
                throw new IllegalStateException("failed to load content security rules file: " + file, e);
            }
        }
        allRules = rf.rules() != null ? new ArrayList<>(rf.rules()) : new ArrayList<>();
        compiledPatterns.clear();
        compiledGlobalPatterns.clear();
        int flags = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        for (ContentSecurityRule rule : allRules) {
            try {
                compiledPatterns.put(rule.id(), Pattern.compile(rule.pattern(), flags));
                compiledGlobalPatterns.put(rule.id(), Pattern.compile(rule.pattern(), flags));
            } catch (Exception e) {
                log.warn("failed to compile regex for rule {}: {}", rule.id(), e.getMessage());
            }
        }
        log.info("content security rules loaded: {} rules, {} patterns compiled", allRules.size(), compiledPatterns.size());
    }

    private void loadAllowlistInternal() {
        Path file = Path.of(allowlistFile).toAbsolutePath();
        ContentSecurityAllowlistFile af;
        if (Files.notExists(file)) {
            af = new ContentSecurityAllowlistFile(0, List.of());
        } else {
            try {
                af = mapper.readValue(file.toFile(), ContentSecurityAllowlistFile.class);
            } catch (Exception e) {
                throw new IllegalStateException("failed to load allowlist file: " + file, e);
            }
        }
        allowlistedIds = af.ruleIds() != null ? new HashSet<>(af.ruleIds()) : new HashSet<>();
        log.info("content security allowlist loaded: {} ids", allowlistedIds.size());
    }

    private void loadForbiddenWordsInternal() {
        Path file = Path.of(forbiddenWordsFile).toAbsolutePath();
        if (Files.notExists(file)) {
            forbiddenWordFile = new ForbiddenWordFile(0, List.of());
        } else {
            try {
                forbiddenWordFile = mapper.readValue(file.toFile(), ForbiddenWordFile.class);
            } catch (Exception e) {
                throw new IllegalStateException("failed to load forbidden words file: " + file, e);
            }
        }
        rebuildPinyinCache(forbiddenWordFile.words());
        log.info("forbidden words loaded: {} words", forbiddenWordFile.words().size());
    }

    private void rebuildPinyinCache(List<String> words) {
        pinyinCache.clear();
        for (String w : words) {
            cachePinyinForWord(w);
        }
    }

    private void cachePinyinForWord(String word) {
        String nw = normalizeForbidden(word);
        if (nw.isEmpty()) return;
        String withTone = toPinyin(nw, true);
        String withoutTone = toPinyin(nw, false);
        pinyinCache.put(nw + "__t", withTone);
        pinyinCache.put(nw, withoutTone);
    }

    // ========== 核心检测入口 ==========

    public ContentSecurityCheckResult checkUserMessages(List<Msg> msgs) {
        LinkedHashSet<String> forbiddenHitsAll = new LinkedHashSet<>();
        ContentDecision globalDecision = ContentDecision.ALLOW;
        double maxConfidence = 0.0;
        ContentRiskLevel globalRiskLevel = ContentRiskLevel.LOW;
        LinkedHashMap<String, ContentSecurityRuleHit> allReasons = new LinkedHashMap<>();
        LinkedHashMap<String, ContentSecurityRuleHit> allAllowlisted = new LinkedHashMap<>();

        if (msgs != null) {
            for (Msg msg : msgs) {
                if (msg.getRole() != MsgRole.USER) continue;
                String text = textOf(msg);
                List<String> fwHits = forbiddenWordCheck(text);
                forbiddenHitsAll.addAll(fwHits);
                PromptInjectionScanResult injection = promptInjectionCheck(text);

                if (injection.confidence() > maxConfidence) {
                    maxConfidence = injection.confidence();
                }
                if (injection.decision().ordinal() > globalDecision.ordinal()) {
                    globalDecision = injection.decision();
                }
                if (injection.riskLevel().ordinal() > globalRiskLevel.ordinal()) {
                    globalRiskLevel = injection.riskLevel();
                }
                for (ContentSecurityRuleHit r : injection.reasons()) {
                    if (!allReasons.containsKey(r.ruleId())) {
                        allReasons.put(r.ruleId(), r);
                    }
                }
                for (ContentSecurityRuleHit r : injection.allowlistedHits()) {
                    if (!allAllowlisted.containsKey(r.ruleId())) {
                        allAllowlisted.put(r.ruleId(), r);
                    }
                }
            }
        }

        PromptInjectionScanResult combinedInjection = new PromptInjectionScanResult(
                globalDecision,
                round3(maxConfidence),
                globalRiskLevel,
                new ArrayList<>(allReasons.values()),
                new ArrayList<>(allAllowlisted.values()),
                List.of()
        );

        boolean shouldBlock = false;
        List<String> tipLines = new ArrayList<>();

        if (!forbiddenHitsAll.isEmpty()) {
            shouldBlock = true;
            tipLines.add("您的消息包含禁用词：" + String.join("、", forbiddenHitsAll));
        }

        if (combinedInjection.decision() == ContentDecision.BLOCK) {
            shouldBlock = true;
            List<String> shown = combinedInjection.reasons().stream().limit(3)
                    .map(h -> "[" + h.severity() + "] " + h.ruleId() + "：" + h.snippet())
                    .collect(Collectors.toList());
            tipLines.add("检测到疑似 Prompt 注入攻击（" + combinedInjection.riskLevel() + " 风险，置信度 "
                    + String.format("%.1f%%", maxConfidence * 100) + "）。命中规则："
                    + String.join("；", shown));
        }

        if (combinedInjection.decision() == ContentDecision.REVIEW) {
            if ("block".equalsIgnoreCase(reviewStrategy)) {
                shouldBlock = true;
                List<String> shown = combinedInjection.reasons().stream().limit(2)
                        .map(h -> h.ruleId() + "（" + h.description() + "）")
                        .collect(Collectors.toList());
                tipLines.add("检测到可疑内容，请修改后重试。命中规则：" + String.join("；", shown));
            } else if ("log-only".equalsIgnoreCase(reviewStrategy)) {
                log.warn("ContentSecurity REVIEW 判决：confidence={} reasons={}，按策略放行不拦截",
                        maxConfidence,
                        combinedInjection.reasons().stream().map(ContentSecurityRuleHit::ruleId).collect(Collectors.toList()));
            }
        }

        String blockTip = String.join("；\n", tipLines);
        if (blockTip.isEmpty() && shouldBlock) {
            blockTip = "⚠ 您的消息存在安全风险，已被拦截，请修改后重新发送";
        }

        return new ContentSecurityCheckResult(
                new ArrayList<>(forbiddenHitsAll),
                combinedInjection,
                shouldBlock,
                blockTip
        );
    }

    // ========== 禁用词检测 ==========

    public List<String> forbiddenWordCheck(String text) {
        List<String> hits = new ArrayList<>();
        if (text == null || text.isBlank()) return hits;
        String normalized = normalizeForbidden(text);
        if (normalized.isEmpty()) return hits;

        String textPinyin = null;
        if (homophoneEnabled) {
            textPinyin = toPinyin(normalized, homophoneToneMatch);
        }

        List<String> words = forbiddenWordFile.words();
        for (String w : words) {
            String nw = normalizeForbidden(w);
            if (!nw.isEmpty() && normalized.contains(nw)) {
                hits.add(w);
                continue;
            }
            if (homophoneEnabled && textPinyin != null && !textPinyin.isEmpty()) {
                String cacheKey = homophoneToneMatch ? nw + "__t" : nw;
                String wp = pinyinCache.get(cacheKey);
                if (wp == null) {
                    wp = toPinyin(nw, homophoneToneMatch);
                    pinyinCache.put(cacheKey, wp);
                }
                if (!wp.isEmpty() && textPinyin.contains(wp)) {
                    hits.add(w);
                }
            }
        }
        return hits;
    }

    private static String normalizeForbidden(String text) {
        if (text == null) return "";
        return text.replaceAll("[\\s\\p{P}\\p{S}\\p{C}]+", "").toLowerCase();
    }

    static String toPinyin(String text, boolean withTone) {
        if (text == null || text.isEmpty()) return "";
        HanyuPinyinOutputFormat format = withTone ? WITH_TONE_FORMAT : WITHOUT_TONE_FORMAT;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c <= 127) {
                sb.append(c);
                continue;
            }
            try {
                String[] arr = PinyinHelper.toHanyuPinyinStringArray(c, format);
                if (arr != null && arr.length > 0) {
                    sb.append(arr[0]);
                } else {
                    sb.append(c);
                }
            } catch (BadHanyuPinyinOutputFormatCombination e) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String textOf(Msg msg) {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock b : msg.getContent()) {
            if (b instanceof TextBlock tb) {
                sb.append(tb.getText());
            }
        }
        return sb.toString();
    }

    // ========== Prompt 注入检测 ==========

    public PromptInjectionScanResult promptInjectionCheck(String text) {
        if (text == null) text = "";

        List<Variant> variants;
        if (variantLayerEnabled) {
            variants = buildVariants(text);
        } else {
            variants = List.of(new Variant(text, "plain"));
        }

        LinkedHashMap<String, ContentSecurityRuleHit> reasonsByRuleId = new LinkedHashMap<>();
        LinkedHashMap<String, ContentSecurityRuleHit> allowlistedByRuleId = new LinkedHashMap<>();
        double score = 0;
        boolean anyCritical = false;

        for (Variant variant : variants) {
            if (anyCritical) break;
            List<ContentSecurityRuleHit> hits = ruleScanOneVariant(variant.text(), variant.via());
            for (ContentSecurityRuleHit hit : hits) {
                if (allowlistedIds.contains(hit.ruleId())) {
                    if (!allowlistedByRuleId.containsKey(hit.ruleId())) {
                        allowlistedByRuleId.put(hit.ruleId(), hit);
                    }
                } else {
                    if (!reasonsByRuleId.containsKey(hit.ruleId())) {
                        reasonsByRuleId.put(hit.ruleId(), hit);
                        score += hit.severity().weight();
                        if (hit.severity() == ContentSeverity.CRITICAL) {
                            anyCritical = true;
                        }
                    }
                }
            }
        }

        List<ContentSecurityRuleHit> reasons = new ArrayList<>(reasonsByRuleId.values());
        List<ContentSecurityRuleHit> allowlistedHits = new ArrayList<>(allowlistedByRuleId.values());

        double confidence;
        if (anyCritical) {
            confidence = Math.min(1.0, Math.max(score, 0.9));
        } else {
            confidence = Math.min(1.0, score);
        }

        ContentDecision decision;
        if (confidence >= blockThreshold) {
            decision = ContentDecision.BLOCK;
        } else if (confidence >= reviewThreshold) {
            decision = ContentDecision.REVIEW;
        } else {
            decision = ContentDecision.ALLOW;
        }

        ContentRiskLevel riskLevel = ContentRiskLevel.LOW;
        for (ContentSecurityRuleHit r : reasons) {
            if (r.severity() == ContentSeverity.CRITICAL || r.severity() == ContentSeverity.HIGH) {
                riskLevel = ContentRiskLevel.HIGH;
                break;
            } else if (r.severity() == ContentSeverity.MEDIUM && riskLevel != ContentRiskLevel.HIGH) {
                riskLevel = ContentRiskLevel.MEDIUM;
            }
        }
        if (confidence >= blockThreshold) {
            riskLevel = ContentRiskLevel.HIGH;
        } else if (confidence >= reviewThreshold && riskLevel == ContentRiskLevel.LOW) {
            riskLevel = ContentRiskLevel.MEDIUM;
        }

        return new PromptInjectionScanResult(
                decision,
                round3(confidence),
                riskLevel,
                reasons,
                allowlistedHits,
                List.of()
        );
    }

    private List<ContentSecurityRuleHit> ruleScanOneVariant(String subject, String via) {
        List<ContentSecurityRuleHit> hits = new ArrayList<>();
        for (ContentSecurityRule rule : allRules) {
            Pattern patternG = compiledGlobalPatterns.get(rule.id());
            if (patternG == null) continue;
            var matcher = patternG.matcher(subject);
            int matches = 0;
            int firstIndex = -1;
            String firstMatch = "";
            while (matcher.find()) {
                if (firstIndex == -1) {
                    firstIndex = matcher.start();
                    firstMatch = matcher.group();
                }
                matches++;
                if (matches >= MAX_MATCHES_PER_RULE) break;
            }
            if (matches > 0) {
                    String snippet = makeSnippet(subject, firstIndex, firstMatch);
                    ContentSecurityRuleHit hit = new ContentSecurityRuleHit(
                            rule.id(), rule.category(), rule.severity(), rule.action(),
                            rule.description(), matches, snippet, via
                    );
                    hits.add(hit);
            }
            if (rule.severity() == ContentSeverity.CRITICAL && matches > 0) {
                break;
            }
        }
        return hits;
    }

    private List<Variant> buildVariants(String subject) {
        List<Variant> variants = new ArrayList<>();
        variants.add(new Variant(subject, "plain"));

        String normalized = normalizeForScan(subject);
        if (!normalized.equals(subject)) {
            variants.add(new Variant(normalized, "normalized"));
        }

        int remaining = VARIANT_CAP - variants.size();
        List<String> b64Candidates = base64Candidates(subject);
        for (int i = 0; i < remaining && i < b64Candidates.size(); i++) {
            variants.add(new Variant(b64Candidates.get(i), "base64"));
        }

        if (variants.size() > VARIANT_CAP) {
            variants = new ArrayList<>(variants.subList(0, VARIANT_CAP));
        }
        return variants;
    }

    private static String normalizeForScan(String text) {
        if (!NEEDS_NORMALIZE_RE.matcher(text).find()) {
            return text;
        }
        String out = text.replaceAll("[\\u200b-\\u200d\\u2060\\ufeff]", "");
        StringBuilder sb = new StringBuilder(out.length());
        for (int i = 0; i < out.length(); i++) {
            char c = out.charAt(i);
            if (c >= 0xFF01 && c <= 0xFF5E) {
                sb.append((char) (c - 0xFEE0));
            } else if (HOMOGLYPH_MAP.containsKey(c)) {
                sb.append(HOMOGLYPH_MAP.get(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static List<String> base64Candidates(String subject) {
        List<String> candidates = new ArrayList<>();
        var m = BASE64_TOKEN_RE.matcher(subject);
        while (m.find() && candidates.size() < MAX_BASE64_TOKENS) {
            String token = m.group();
            String decoded = tryDecodeBase64(token);
            if (decoded != null && !decoded.equals(token)) {
                candidates.add(decoded);
            }
        }
        return candidates;
    }

    private static String tryDecodeBase64(String token) {
        if (token.length() < 16 || token.length() > MAX_BASE64_LEN) {
            return null;
        }
        int pad = (4 - (token.length() % 4)) % 4;
        String padded;
        if (pad > 0) {
            padded = token + "=".repeat(pad);
        } else {
            padded = token;
        }
        try {
            byte[] decodedBytes = Base64.getDecoder().decode(padded);
            String decoded = new String(decodedBytes, StandardCharsets.UTF_8);
            if (decoded.length() < 8) return null;
            long printable = 0;
            int len = decoded.length();
            for (int i = 0; i < len; i++) {
                int cp = decoded.charAt(i);
                if (cp == 9 || cp == 10 || cp == 13 || cp >= 32) printable++;
            }
            if ((double) printable / len >= 0.8) {
                return decoded;
            } else {
                return null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static String makeSnippet(String subject, int firstIndex, String matched) {
        int start = Math.max(0, firstIndex - SNIPPET_RADIUS);
        int end = Math.min(subject.length(), firstIndex + matched.length() + SNIPPET_RADIUS);
        String raw = subject.substring(start, end);
        raw = CONTROL_CHAR_RE.matcher(raw).replaceAll(" ");
        raw = WHITESPACE_RE.matcher(raw).replaceAll(" ").trim();
        String redacted = maskCnMobile(raw);
        redacted = maskCnIdCard(redacted);
        if (redacted.length() > SNIPPET_MAX) {
            redacted = redacted.substring(0, SNIPPET_MAX) + "…";
        }
        return redacted;
    }

    private static String maskCnMobile(String text) {
        return CN_MOBILE_RE.matcher(text).replaceAll("$1****$3");
    }

    private static String maskCnIdCard(String text) {
        return CN_IDCARD_RE.matcher(text).replaceAll("$1**********$2");
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    private record Variant(String text, String via) {}

    // ========== CRUD 管理方法 ==========

    public ContentSecurityRuleFile loadAllRules() {
        Path file = Path.of(rulesFile).toAbsolutePath();
        if (Files.notExists(file)) {
            return new ContentSecurityRuleFile(0, List.of());
        }
        try {
            return mapper.readValue(file.toFile(), ContentSecurityRuleFile.class);
        } catch (Exception e) {
            throw new IllegalStateException("failed to read rules file: " + file, e);
        }
    }

    public synchronized ContentSecurityRuleFile addRule(ContentSecurityRule rule) {
        if (rule == null || rule.id() == null || rule.id().isBlank()) {
            throw new ApiException(400, "规则 ID 不能为空");
        }
        Path file = Path.of(rulesFile).toAbsolutePath();
        ContentSecurityRuleFile rf = Files.exists(file) ? readRules(file) : new ContentSecurityRuleFile(0, List.of());
        List<ContentSecurityRule> rules = new ArrayList<>(rf.rules());
        for (ContentSecurityRule r : rules) {
            if (r.id().equals(rule.id())) {
                throw new ApiException(409, "规则 ID 已存在: " + rule.id());
            }
        }
        rules.add(rule);
        ContentSecurityRuleFile next = new ContentSecurityRuleFile(rf.version() + 1, rules);
        writeRules(file, next);
        reloadAll();
        return next;
    }

    public synchronized ContentSecurityRuleFile deleteRule(String ruleId) {
        if (ruleId == null || ruleId.isBlank()) {
            throw new ApiException(400, "规则 ID 不能为空");
        }
        Path file = Path.of(rulesFile).toAbsolutePath();
        ContentSecurityRuleFile rf = Files.exists(file) ? readRules(file) : new ContentSecurityRuleFile(0, List.of());
        List<ContentSecurityRule> rules = new ArrayList<>(rf.rules());
        boolean removed = rules.removeIf(r -> r.id().equals(ruleId));
        if (!removed) throw new ApiException(404, "规则不存在: " + ruleId);
        ContentSecurityRuleFile next = new ContentSecurityRuleFile(rf.version() + 1, rules);
        writeRules(file, next);
        reloadAll();
        return next;
    }

    public ContentSecurityAllowlistFile loadAllowlist() {
        Path file = Path.of(allowlistFile).toAbsolutePath();
        if (Files.notExists(file)) {
            return new ContentSecurityAllowlistFile(0, List.of());
        }
        try {
            return mapper.readValue(file.toFile(), ContentSecurityAllowlistFile.class);
        } catch (Exception e) {
            throw new IllegalStateException("failed to read allowlist file: " + file, e);
        }
    }

    public synchronized ContentSecurityAllowlistFile addAllowlist(String ruleId) {
        if (ruleId == null || ruleId.isBlank()) {
            throw new ApiException(400, "规则 ID 不能为空");
        }
        Path file = Path.of(allowlistFile).toAbsolutePath();
        ContentSecurityAllowlistFile af = Files.exists(file) ? readAllowlist(file) : new ContentSecurityAllowlistFile(0, List.of());
        List<String> ids = new ArrayList<>(af.ruleIds());
        if (ids.contains(ruleId)) {
            throw new ApiException(409, "规则 ID 已在 allowlist: " + ruleId);
        }
        ids.add(ruleId);
        ContentSecurityAllowlistFile next = new ContentSecurityAllowlistFile(af.version() + 1, ids);
        writeAllowlist(file, next);
        reloadAll();
        return next;
    }

    public synchronized ContentSecurityAllowlistFile removeAllowlist(String ruleId) {
        if (ruleId == null || ruleId.isBlank()) {
            throw new ApiException(400, "规则 ID 不能为空");
        }
        Path file = Path.of(allowlistFile).toAbsolutePath();
        ContentSecurityAllowlistFile af = Files.exists(file) ? readAllowlist(file) : new ContentSecurityAllowlistFile(0, List.of());
        List<String> ids = new ArrayList<>(af.ruleIds());
        boolean removed = ids.remove(ruleId);
        if (!removed) throw new ApiException(404, "规则 ID 不在 allowlist: " + ruleId);
        ContentSecurityAllowlistFile next = new ContentSecurityAllowlistFile(af.version() + 1, ids);
        writeAllowlist(file, next);
        reloadAll();
        return next;
    }

    public ForbiddenWordFile loadAllForbiddenWords() {
        Path file = Path.of(forbiddenWordsFile).toAbsolutePath();
        if (Files.notExists(file)) {
            return new ForbiddenWordFile(0, List.of());
        }
        try {
            return mapper.readValue(file.toFile(), ForbiddenWordFile.class);
        } catch (Exception e) {
            throw new IllegalStateException("failed to read forbidden words file: " + file, e);
        }
    }

    public synchronized ForbiddenWordFile addForbiddenWord(String word) {
        if (word == null || word.isBlank()) {
            throw new ApiException(400, "禁用词不能为空");
        }
        String trimmed = word.trim();
        Path file = Path.of(forbiddenWordsFile).toAbsolutePath();
        ForbiddenWordFile pf = Files.exists(file) ? readForbidden(file) : new ForbiddenWordFile(0, List.of());
        List<String> words = new ArrayList<>(pf.words());
        if (words.contains(trimmed)) {
            throw new ApiException(409, "禁用词已存在: " + trimmed);
        }
        words.add(trimmed);
        ForbiddenWordFile next = new ForbiddenWordFile(pf.version() + 1, words);
        writeForbidden(file, next);
        reloadAll();
        return next;
    }

    public synchronized ForbiddenWordFile deleteForbiddenWord(String word) {
        if (word == null || word.isBlank()) {
            throw new ApiException(400, "禁用词不能为空");
        }
        Path file = Path.of(forbiddenWordsFile).toAbsolutePath();
        ForbiddenWordFile pf = Files.exists(file) ? readForbidden(file) : new ForbiddenWordFile(0, List.of());
        List<String> words = new ArrayList<>(pf.words());
        boolean removed = words.remove(word);
        if (!removed) throw new ApiException(404, "禁用词不存在: " + word);
        ForbiddenWordFile next = new ForbiddenWordFile(pf.version() + 1, words);
        writeForbidden(file, next);
        reloadAll();
        return next;
    }

    // ========== 文件读写辅助方法 ==========

    private ContentSecurityRuleFile readRules(Path file) {
        try {
            return mapper.readValue(file.toFile(), ContentSecurityRuleFile.class);
        } catch (Exception e) {
            throw new IllegalStateException("failed to load rules file: " + file.toAbsolutePath(), e);
        }
    }

    private void writeRules(Path file, ContentSecurityRuleFile next) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), next);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            throw new IllegalStateException("failed to save rules file: " + file.toAbsolutePath(), e);
        }
    }

    private ContentSecurityAllowlistFile readAllowlist(Path file) {
        try {
            return mapper.readValue(file.toFile(), ContentSecurityAllowlistFile.class);
        } catch (Exception e) {
            throw new IllegalStateException("failed to load allowlist file: " + file.toAbsolutePath(), e);
        }
    }

    private void writeAllowlist(Path file, ContentSecurityAllowlistFile next) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), next);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            throw new IllegalStateException("failed to save allowlist file: " + file.toAbsolutePath(), e);
        }
    }

    private ForbiddenWordFile readForbidden(Path file) {
        try {
            return mapper.readValue(file.toFile(), ForbiddenWordFile.class);
        } catch (Exception e) {
            throw new IllegalStateException("failed to load forbidden words file: " + file.toAbsolutePath(), e);
        }
    }

    private void writeForbidden(Path file, ForbiddenWordFile next) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), next);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            throw new IllegalStateException("failed to save forbidden words file: " + file.toAbsolutePath(), e);
        }
    }
}
