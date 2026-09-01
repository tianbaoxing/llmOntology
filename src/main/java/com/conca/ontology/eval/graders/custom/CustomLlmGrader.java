package com.conca.ontology.eval.graders.custom;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.conca.ontology.eval.CustomGraderDefinition;
import com.conca.ontology.eval.GraderInput;
import com.conca.ontology.eval.GraderScore;
import com.conca.ontology.eval.JudgeClient;
import com.conca.ontology.eval.graders.llm.LlmJudgeGrader;

/**
 * 自定义 LLM 评测器（用户提示词驱动，LlmJudgeGrader 的用户实例化）：
 * - criteria 模式：定义 prompt 作为评分标准（criteria() 覆盖），区块拼接与判分链路
 *   全复用基类（JudgeClient / 归一化 / 重试 / judgeRuns 平均 / 短路规则）；
 * - template 模式：定义 prompt 为完整模板，五占位符
 *   {{query}}/{{answer}}/{{reference}}/{{context}}/{{rubric}} 经 variables() 渲染后
 *   作为 judge 用户消息主体（追加 JSON 输出提醒，守则仍在 judge sysPrompt）。
 * 包路径 graders.custom → groupOf 归「custom」分组，页面单独分区展示。
 * 每个 GraderScore 附 metadata.custom.promptHash（prompt 的 SHA-256 前缀），
 * Run Diff 据此标注「评测器定义已变更，对比仅供参考」。
 */
public class CustomLlmGrader extends LlmJudgeGrader {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(\\w+)}}");

    private final CustomGraderDefinition def;

    public CustomLlmGrader(JudgeClient client, CustomGraderDefinition def) {
        super(client);
        this.def = def;
    }

    public CustomGraderDefinition definition() {
        return def;
    }

    @Override
    public String name() {
        return def.name();
    }

    @Override
    public String description() {
        return def.description();
    }

    @Override
    protected String criteria() {
        // criteria 模式：用户提示词整体作为评分标准区块（样本内容在框架固定区块内，隔离防注入）
        return def.prompt();
    }

    @Override
    protected String userPrompt(GraderInput input) {
        if (!CustomGraderDefinition.MODE_TEMPLATE.equals(def.mode())) {
            return super.userPrompt(input); // criteria 模式全复用基类
        }
        Map<String, String> v = variables(input);
        StringBuilder body = new StringBuilder();
        Matcher m = PLACEHOLDER.matcher(def.prompt());
        while (m.find()) {
            m.appendReplacement(body, Matcher.quoteReplacement(v.getOrDefault(m.group(1), m.group(0))));
        }
        m.appendTail(body);
        // JSON 输出守则固定在 judge agent sysPrompt，此处仅追加轻量提醒（模板全文由用户掌控）
        return body + "\n\n【输出要求】仅输出 JSON：{\"score\": 1-5 整数分, \"reason\": \"简短中文理由\"}";
    }

    /** 附定义指纹（promptHash），供结果落盘溯源与 Run Diff 定义变更检测 */
    @Override
    public GraderScore evaluate(GraderInput input) {
        GraderScore s = super.evaluate(input);
        if (s.error()) {
            return s;
        }
        Map<String, Object> meta = new LinkedHashMap<>(s.metadata());
        Map<String, Object> custom = new LinkedHashMap<>();
        custom.put("name", def.name());
        custom.put("promptHash", promptHash());
        meta.put("custom", custom);
        return new GraderScore(s.name(), s.score(), s.reason(), false, meta);
    }

    /** prompt 的 SHA-256 前 12 位十六进制（比对定义是否变更足够） */
    public String promptHash() {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            StringBuilder hex = new StringBuilder();
            for (byte b : md.digest(def.prompt().getBytes(StandardCharsets.UTF_8))) {
                hex.append(String.format("%02x", b));
            }
            return hex.substring(0, 12);
        } catch (Exception e) {
            return "unknown"; // SHA-256 必然可用，兜底不阻断判分
        }
    }
}
