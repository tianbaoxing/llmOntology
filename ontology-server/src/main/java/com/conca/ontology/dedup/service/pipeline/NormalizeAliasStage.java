package com.conca.ontology.dedup.service.pipeline;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * 规范化别名阶段（A-1）：
 *   对每个类的 keyProp/name 类属性做标准化，生成 1~N 个 normKey。
 *   规范化后完全相同的节点 → 直接判定为重复候选。
 *
 * 规范化顺序（按粒度从粗到细，每个节点生成多 key 以覆盖不同剥离场景）：
 *   1. 统一全角半角标点 + 空白压缩 + 首尾 trim
 *   2. 去除括号内注释【】（）() [] 以及括号本身
 *   3. 剥离后缀表（按 label 不同使用不同的后缀词典）：
 *        Place → 遗址、墓群、墓葬、博物馆、景区、风景区、市、省、自治区、区、县、镇、村
 *        Era   → 朝、代、时期、时代、年间
 *        其他类 → 无专属后缀（只用通用 1+2 两步）
 *   4. 前后缀剥离后再次 trim + 空值丢弃
 *
 * 最终以 List<normKey> 同 label 内做等值分组。
 */
@Component
public class NormalizeAliasStage {

    private static final Pattern BRACKETS_RE = Pattern.compile(
            "[（(\\[【][^）)\\]】]*[）)\\]】]");
    private static final Pattern WHITESPACE_RE = Pattern.compile("[\\s\\u3000\\t]+");

    private static final String[] PLACE_SUFFIXES = {
            "遗址博物馆", "遗址公园", "博物馆", "考古遗址", "墓群", "墓葬", "墓地",
            "风景区", "景区", "旅游区", "自治区", "自治州", "自治县",
            "特别行政区", "省", "市", "区", "县", "镇", "乡", "村", "遗址"
    };
    private static final String[] ERA_SUFFIXES = {
            "年间", "时期", "时代", "王朝", "朝代", "代", "朝"
    };

    /** 输入节点（同 label 批处理）：key=内部节点标识，value=待规范化的字符串（name） */
    public record NodeName(String nodeRef, String label, String name, long totalDegree, int propCount) {}

    /** 输出匹配组：同一 normKey 下所有 NodeName 引用（size≥2 才算候选组）；subStage 恒等于 "norm" */
    public record MatchBucket(String normKey, String subStage, List<NodeName> nodes) {}

    public List<MatchBucket> run(List<NodeName> nodes) {
        Map<String, List<NodeName>> buckets = new LinkedHashMap<>();
        for (NodeName n : nodes) {
            if (n.name == null || n.name.isBlank()) continue;
            for (String nk : generateNormKeys(n.name, n.label)) {
                buckets.computeIfAbsent(nk + "|" + n.label, _k -> new ArrayList<>()).add(n);
            }
        }
        List<MatchBucket> out = new ArrayList<>();
        for (Map.Entry<String, List<NodeName>> e : buckets.entrySet()) {
            if (e.getValue().size() >= 2) {
                String nk = e.getKey().substring(0, e.getKey().indexOf('|'));
                out.add(new MatchBucket(nk, "norm", List.copyOf(e.getValue())));
            }
        }
        return out;
    }

    List<String> generateNormKeys(String rawName, String label) {
        String base = baseNormalize(rawName);
        if (base.isEmpty()) return List.of();
        List<String> out = new ArrayList<>(4);
        out.add(base);
        String[] suffixes = suffixesFor(label);
        String stripped = base;
        boolean changed;
        do {
            changed = false;
            for (String sfx : suffixes) {
                if (stripped.endsWith(sfx) && stripped.length() > sfx.length()) {
                    stripped = stripped.substring(0, stripped.length() - sfx.length()).trim();
                    changed = true;
                    if (!stripped.isEmpty() && !out.contains(stripped)) {
                        out.add(stripped);
                    }
                }
            }
        } while (changed);
        if (!out.contains(base)) out.add(0, base);
        return out;
    }

    private String[] suffixesFor(String label) {
        if (label == null) return new String[0];
        return switch (label) {
            case "Place" -> PLACE_SUFFIXES;
            case "Era"   -> ERA_SUFFIXES;
            default      -> new String[0];
        };
    }

    /** 基础规范化：全角括号去内容 + 全角半角标点统一 + 空白压缩 */
    public String baseNormalize(String raw) {
        if (raw == null) return "";
        String s = raw;
        Matcher m;
        do {
            m = BRACKETS_RE.matcher(s);
            if (m.find()) s = m.replaceAll("");
        } while (m.find());
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '（' -> sb.append('(');
                case '）' -> sb.append(')');
                case '【' -> sb.append('[');
                case '】' -> sb.append(']');
                case '「' -> sb.append('"');
                case '」' -> sb.append('"');
                case '『' -> sb.append('\'');
                case '』' -> sb.append('\'');
                case '、' -> sb.append(',');
                case '，' -> sb.append(',');
                case '。' -> sb.append('.');
                case '：' -> sb.append(':');
                case '；' -> sb.append(';');
                case '！' -> sb.append('!');
                case '？' -> sb.append('?');
                case '\u3000' -> sb.append(' ');
                default   -> sb.append(c);
            }
        }
        s = WHITESPACE_RE.matcher(sb.toString()).replaceAll(" ").trim();
        return s;
    }
}
