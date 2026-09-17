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
import com.conca.ontology.model.content.*;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import jakarta.annotation.PostConstruct;

@Service
public class ContentSecurityService {

    private static final Logger log = LoggerFactory.getLogger(ContentSecurityService.class);

    private static final int MAX_MATCHES_PER_RULE = 20;
    private static final int SNIPPET_RADIUS = 30;
    private static final int SNIPPET_MAX = 100;
    private static final int VARIANT_CAP = 4;
    private static final int MAX_BASE64_TOKENS = 4;
    private static final int MAX_BASE64_LEN = 4096;

    private static final Map<Character, Character> HOMOGLYPH_MAP;
    private static final Pattern NEEDS_NORMALIZE_RE;
    private static final Pattern BASE64_TOKEN_RE;
    private static final Pattern CN_MOBILE_RE;
    private static final Pattern CN_IDCARD_RE;
    private static final Pattern CONTROL_CHAR_RE;
    private static final Pattern WHITESPACE_RE;

    static {
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
    private String legacyAllowlistFile;

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

    public ContentSecurityService(ObjectMapper mapper) {
        this.mapper = mapper;
        this.compiledPatterns = new HashMap<>();
        this.compiledGlobalPatterns = new HashMap<>();
        this.allRules = new ArrayList<>();
        this.allowlistedIds = new HashSet<>();
    }

    @PostConstruct
    public synchronized void reloadAll() {
        migrateLegacyAllowlistIfNeeded();
        loadRulesInternal();
    }

    // ========== 文件装载内部方法 ==========

    private void migrateLegacyAllowlistIfNeeded() {
        Path rulesPath = Path.of(rulesFile).toAbsolutePath();
        Path legacyPath = Path.of(legacyAllowlistFile).toAbsolutePath();
        if (Files.notExists(legacyPath)) return;
        try {
            ContentSecurityRuleFile rf = Files.exists(rulesPath)
                    ? mapper.readValue(rulesPath.toFile(), ContentSecurityRuleFile.class)
                    : new ContentSecurityRuleFile(0, List.of());
            ContentSecurityAllowlistFile af = mapper.readValue(legacyPath.toFile(), ContentSecurityAllowlistFile.class);
            Set<String> legacyIds = new HashSet<>(af.ruleIds() != null ? af.ruleIds() : List.of());
            if (legacyIds.isEmpty()) {
                Files.deleteIfExists(legacyPath);
                log.info("content security legacy allowlist empty, removed file");
                return;
            }
            List<ContentSecurityRule> rules = new ArrayList<>(rf.rules() != null ? rf.rules() : List.of());
            boolean changed = false;
            for (int i = 0; i < rules.size(); i++) {
                ContentSecurityRule r = rules.get(i);
                if (legacyIds.contains(r.id()) && !r.allowlistedEffective()) {
                    rules.set(i, new ContentSecurityRule(r.id(), r.category(), r.severity(), r.action(),
                            r.description(), r.pattern(), Boolean.TRUE));
                    changed = true;
                }
            }
            if (changed) {
                ContentSecurityRuleFile next = new ContentSecurityRuleFile(rf.version() + 1, rules);
                writeRules(rulesPath, next);
            }
            Files.deleteIfExists(legacyPath);
            log.info("content security legacy allowlist migrated: {} ids merged into rules file, legacy file removed", legacyIds.size());
        } catch (Exception e) {
            log.warn("failed to migrate legacy content security allowlist ({}): {}", legacyPath, e.getMessage());
        }
    }

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
        allowlistedIds = new HashSet<>();
        for (ContentSecurityRule r : allRules) {
            if (r.allowlistedEffective()) allowlistedIds.add(r.id());
        }
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
        log.info("content security rules loaded: {} rules ({} allowlisted), {} patterns compiled",
                allRules.size(), allowlistedIds.size(), compiledPatterns.size());
    }

    // ========== 核心检测入口 ==========

    public ContentSecurityCheckResult checkUserMessages(List<Msg> msgs) {
        ContentDecision globalDecision = ContentDecision.ALLOW;
        double maxConfidence = 0.0;
        ContentRiskLevel globalRiskLevel = ContentRiskLevel.LOW;
        LinkedHashMap<String, ContentSecurityRuleHit> allReasons = new LinkedHashMap<>();
        LinkedHashMap<String, ContentSecurityRuleHit> allAllowlisted = new LinkedHashMap<>();

        if (msgs != null) {
            for (Msg msg : msgs) {
                if (msg.getRole() != MsgRole.USER) continue;
                String text = textOf(msg);
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
                combinedInjection,
                shouldBlock,
                blockTip
        );
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

    public ContentSecurityAllowlistFile loadAllowlist() {
        List<String> ids = allRules.stream()
                .filter(ContentSecurityRule::allowlistedEffective)
                .map(ContentSecurityRule::id)
                .toList();
        int version = loadAllRules().version();
        return new ContentSecurityAllowlistFile(version, ids);
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
        Boolean alw = rule.allowlisted() != null ? rule.allowlisted() : Boolean.FALSE;
        rules.add(new ContentSecurityRule(rule.id(), rule.category(), rule.severity(), rule.action(),
                rule.description(), rule.pattern(), alw));
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

    public synchronized ContentSecurityAllowlistFile addAllowlist(String ruleId) {
        if (ruleId == null || ruleId.isBlank()) {
            throw new ApiException(400, "规则 ID 不能为空");
        }
        Path file = Path.of(rulesFile).toAbsolutePath();
        ContentSecurityRuleFile rf = Files.exists(file) ? readRules(file) : new ContentSecurityRuleFile(0, List.of());
        List<ContentSecurityRule> rules = new ArrayList<>(rf.rules());
        boolean found = false;
        for (int i = 0; i < rules.size(); i++) {
            ContentSecurityRule r = rules.get(i);
            if (r.id().equals(ruleId)) {
                if (r.allowlistedEffective()) {
                    throw new ApiException(409, "规则 ID 已在 allowlist: " + ruleId);
                }
                rules.set(i, new ContentSecurityRule(r.id(), r.category(), r.severity(), r.action(),
                        r.description(), r.pattern(), Boolean.TRUE));
                found = true;
                break;
            }
        }
        if (!found) throw new ApiException(404, "规则不存在: " + ruleId);
        ContentSecurityRuleFile next = new ContentSecurityRuleFile(rf.version() + 1, rules);
        writeRules(file, next);
        reloadAll();
        return new ContentSecurityAllowlistFile(next.version(),
                next.rules().stream().filter(ContentSecurityRule::allowlistedEffective).map(ContentSecurityRule::id).toList());
    }

    public synchronized ContentSecurityAllowlistFile removeAllowlist(String ruleId) {
        if (ruleId == null || ruleId.isBlank()) {
            throw new ApiException(400, "规则 ID 不能为空");
        }
        Path file = Path.of(rulesFile).toAbsolutePath();
        ContentSecurityRuleFile rf = Files.exists(file) ? readRules(file) : new ContentSecurityRuleFile(0, List.of());
        List<ContentSecurityRule> rules = new ArrayList<>(rf.rules());
        boolean found = false;
        for (int i = 0; i < rules.size(); i++) {
            ContentSecurityRule r = rules.get(i);
            if (r.id().equals(ruleId)) {
                if (!r.allowlistedEffective()) {
                    throw new ApiException(404, "规则 ID 不在 allowlist: " + ruleId);
                }
                rules.set(i, new ContentSecurityRule(r.id(), r.category(), r.severity(), r.action(),
                        r.description(), r.pattern(), Boolean.FALSE));
                found = true;
                break;
            }
        }
        if (!found) throw new ApiException(404, "规则不存在: " + ruleId);
        ContentSecurityRuleFile next = new ContentSecurityRuleFile(rf.version() + 1, rules);
        writeRules(file, next);
        reloadAll();
        return new ContentSecurityAllowlistFile(next.version(),
                next.rules().stream().filter(ContentSecurityRule::allowlistedEffective).map(ContentSecurityRule::id).toList());
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
}
