package com.conca.ontology.disjoint.tool;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.conca.ontology.disjoint.dto.DisjointRule;
import com.conca.ontology.disjoint.dto.DisjointRulesDTO;
import com.conca.ontology.disjoint.service.DisjointRuleService;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;

/**
 * 互斥约束词典工具：Agent 在任何"最终输出前 / 写入数据前"的强制前置步骤。
 *
 * 只提供 list（获取规则列表），不提供 check 预判定——
 * Skill 获取规则后，由 LLM **自行比对"即将输出/写入的内容"与规则中的【】候选项**，
 * 若发现同一条规则内 ≥2 个候选项同时出现在内容中，必须直接终止并报错。
 * 这么做是为了让 LLM 自己理解语义与匹配边界，而不是依赖简单字符串包含。
 *
 * @author tianbaoxing
 * @date 2026-09-11
 */
public class DisjointRulesTool {

    private static final Logger log = LoggerFactory.getLogger(DisjointRulesTool.class);
    private static final Pattern BRACKET = Pattern.compile("【([^】]+)】");

    private final DisjointRuleService service;

    public DisjointRulesTool(DisjointRuleService service) {
        this.service = service;
    }

    public static List<String> parseItems(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        Matcher m = BRACKET.matcher(text);
        while (m.find()) {
            String s = m.group(1).trim();
            if (!s.isBlank() && !out.contains(s)) out.add(s);
        }
        return out;
    }

    @Tool(name = "disjoint_rules_list", readOnly = true, concurrencySafe = true,
            description = "获取项目中的互斥约束词典（CRM-ACA 建模层幻觉抑制红线，本体 DisjointClasses 简化版）。"
                    + "返回所有规则原文：每条规则是一段中文文本，互斥的候选项用【】括号包裹。"
                    + "何时必须调用：在任何'生成最终面向用户的事实性回复'之前；在任何'拟写入图谱的实体/关系/属性中文内容'落库之前。"
                    + "LLM 拿到规则列表后必须做的动作：把【】中的候选项与'即将输出/写入的内容'逐条比对，"
                    + "若发现同一条规则内有 ≥2 个候选项同时出现在内容中（含同义词/近义表述），"
                    + "必须直接终止本次输出/写入，按 SKILL.md 中的冲突模板向用户报告，"
                    + "不得改写、不得绕开、不得把冲突内容当作事实输出或落库。"
                    + "无参数，调用一次返回全部规则。")
    public String list(RuntimeContext ctx) {
        try {
            DisjointRulesDTO f = service.loadAll();
            StringBuilder sb = new StringBuilder();
            sb.append("# 互斥约束词典（DisjointClasses 简化版 · CRM-ACA 建模红线）\n");
            sb.append("count: ").append(f.rules().size()).append('\n');
            sb.append("## LLM 必须遵守的判定规则\n");
            sb.append("拿到本列表后，在你输出任何事实性结论/写入任何图谱数据之前，执行以下比对：\n");
            sb.append("1. 逐条阅读下方规则，提取每条规则中【】包裹的所有候选项（记为候选集合 S）。\n");
            sb.append("2. 检查你即将输出/写入的那段文本，看 S 中是否有 ≥2 个候选项（含近义/同义表达）同时出现在文本中。\n");
            sb.append("3. 只要有任何一条规则触发 → **必须直接终止本次输出/写入**，不再继续生成。按 skill 给出的冲突话术模板报告用户。\n");
            sb.append("4. 所有规则均未触发 → 正常输出/写入。\n");
            sb.append("## 规则详情\n");
            for (DisjointRule r : f.rules()) {
                List<String> items = parseItems(r.text());
                sb.append("- [").append(r.id()).append("] ").append(r.text());
                if (!items.isEmpty()) {
                    sb.append("  ← 候选集合 S = {").append(String.join("、", items)).append("}");
                }
                sb.append('\n');
            }
            return sb.toString().stripTrailing();
        } catch (Exception e) {
            log.error("disjoint_rules_list failed", e);
            return "[工具错误] disjoint_rules_list 执行失败：" + e.getMessage();
        }
    }
}
