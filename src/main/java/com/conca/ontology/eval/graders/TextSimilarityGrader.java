package com.conca.ontology.eval.graders;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.conca.ontology.eval.Grader;
import com.conca.ontology.eval.GraderInput;
import com.conca.ontology.eval.GraderScore;

/**
 * 文本相似度评测（二期规则补强，OpenJudge SimilarityGrader 的中文可用子集）：
 * reference_response 为参考文本（数组时顿号连接），中文按字符处理。
 * score = 字符 unigram F1（rouge1），衡量开放式回答对参考简介的覆盖度；
 * metadata 另附 rougeL / bigram-F1 / jaccard / 编辑距离相似度共 5 种指标。
 */
@Component
public class TextSimilarityGrader implements Grader {

    /** 参与比对的最大字符数（防超长文本 DP 爆炸） */
    private static final int MAX_LEN = 1000;

    @Override
    public String name() {
        return "text_similarity";
    }

    @Override
    public String description() {
        return "文本相似度：回答与 reference_response 的字符级 rouge1-F1 得分（0-1），metadata 附 rougeL/bigram-F1/jaccard/编辑距离";
    }

    @Override
    public GraderScore evaluate(GraderInput input) {
        Object ref = input.referenceResponse();
        if (ref == null) {
            return GraderScore.fail(name(), "[参数错误] reference_response 缺失，无法计算相似度");
        }
        String refText = ref instanceof List<?> l && !l.isEmpty()
                ? l.stream().map(String::valueOf).reduce((a, b) -> a + "、" + b).orElse("")
                : String.valueOf(ref);
        if (refText.isBlank()) {
            return GraderScore.fail(name(), "[参数错误] reference_response 为空，无法计算相似度");
        }
        if (input.record().failed()) {
            return GraderScore.fail(name(), "agent 调用失败：" + input.record().error());
        }
        String answer = input.response() == null ? "" : input.response();
        String a = clip(answer);
        String r = clip(refText);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("answerLength", answer.length());
        meta.put("referenceLength", refText.length());
        if (a.isEmpty()) {
            return GraderScore.of(name(), 0.0, "回答为空，相似度 0", meta);
        }
        double rouge1 = ngramF1(a, r, 1);
        double rougeL = lcsF1(a, r);
        double bigramF1 = ngramF1(a, r, 2);
        double jaccard = bigramJaccard(a, r);
        double editSim = editSimilarity(a, r);
        meta.put("rouge1", round4(rouge1));
        meta.put("rougeL", round4(rougeL));
        meta.put("bigramF1", round4(bigramF1));
        meta.put("jaccard", round4(jaccard));
        meta.put("editSimilarity", round4(editSim));
        return GraderScore.of(name(), rouge1,
                "字符 rouge1-F1=" + round4(rouge1) + "（rougeL=" + round4(rougeL)
                        + "，jaccard=" + round4(jaccard) + "，编辑距离相似度=" + round4(editSim) + "）",
                meta);
    }

    private static String clip(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        return s.length() <= MAX_LEN ? s : s.substring(0, MAX_LEN);
    }

    /** n-gram 多重集 F1（n=1 即字符 rouge1） */
    static double ngramF1(String a, String b, int n) {
        Map<String, Integer> ba = ngramCounts(a, n);
        Map<String, Integer> bb = ngramCounts(b, n);
        int ta = total(ba);
        int tb = total(bb);
        if (ta == 0 || tb == 0) {
            return 0;
        }
        long overlap = 0;
        for (Map.Entry<String, Integer> e : ba.entrySet()) {
            Integer other = bb.get(e.getKey());
            if (other != null) {
                overlap += Math.min(e.getValue(), other);
            }
        }
        if (overlap == 0) {
            return 0;
        }
        double p = overlap * 1.0 / ta;
        double r = overlap * 1.0 / tb;
        return 2 * p * r / (p + r);
    }

    private static Map<String, Integer> ngramCounts(String s, int n) {
        Map<String, Integer> m = new HashMap<>();
        for (int i = 0; i + n <= s.length(); i++) {
            m.merge(s.substring(i, i + n), 1, Integer::sum);
        }
        return m;
    }

    private static int total(Map<String, Integer> m) {
        return m.values().stream().mapToInt(Integer::intValue).sum();
    }

    /** 字符 LCS F1（rougeL），滚动数组 DP */
    static double lcsF1(String a, String b) {
        int n = a.length();
        int m = b.length();
        if (n == 0 || m == 0) {
            return 0;
        }
        int[] prev = new int[m + 1];
        int[] cur = new int[m + 1];
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++) {
                cur[j] = a.charAt(i - 1) == b.charAt(j - 1)
                        ? prev[j - 1] + 1
                        : Math.max(prev[j], cur[j - 1]);
            }
            int[] tmp = prev;
            prev = cur;
            cur = tmp;
            java.util.Arrays.fill(cur, 0);
        }
        int lcs = prev[m];
        if (lcs == 0) {
            return 0;
        }
        double p = lcs * 1.0 / n;
        double r = lcs * 1.0 / m;
        return 2 * p * r / (p + r);
    }

    /** 字符 bigram 集合 Jaccard */
    static double bigramJaccard(String a, String b) {
        var sa = ngramCounts(a, 2).keySet();
        var sb = ngramCounts(b, 2).keySet();
        if (sa.isEmpty() && sb.isEmpty()) {
            return a.equals(b) ? 1.0 : 0.0;
        }
        long inter = sa.stream().filter(sb::contains).count();
        long union = sa.size() + sb.size() - inter;
        return union == 0 ? 0 : inter * 1.0 / union;
    }

    /** 归一化编辑距离相似度（1 - 距离/较长串长度），滚动数组 DP */
    static double editSimilarity(String a, String b) {
        int n = a.length();
        int m = b.length();
        if (n == 0 && m == 0) {
            return 1.0;
        }
        if (n == 0 || m == 0) {
            return 0.0;
        }
        int[] prev = new int[m + 1];
        int[] cur = new int[m + 1];
        for (int j = 0; j <= m; j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= n; i++) {
            cur[0] = i;
            for (int j = 1; j <= m; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = cur;
            cur = tmp;
        }
        return 1.0 - prev[m] * 1.0 / Math.max(n, m);
    }

    static double round4(double v) {
        return Math.round(v * 10000) / 10000.0;
    }
}
