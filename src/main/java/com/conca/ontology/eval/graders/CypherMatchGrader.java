package com.conca.ontology.eval.graders;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.conca.ontology.eval.AgentRecord;
import com.conca.ontology.eval.EvalSample;
import com.conca.ontology.eval.Grader;
import com.conca.ontology.eval.GraderScore;
import com.conca.ontology.service.GraphQueryService;

/**
 * Cypher 结果比对评测（本项目特色：以图谱为标准答案）：
 * expected = {type:"cypher", cypher:"MATCH ... RETURN count(n) AS c", value:123, tolerance:0}。
 * 执行标准查询取第一行第一列数值，与 agent 回答中提取的数字容差比对，命中得 1.0。
 * 安全双保险：本类预检（全文写关键词白名单）+ GraphQueryService 服务端只读红线。
 */
@Component
public class CypherMatchGrader implements Grader {

    /** 语句中任何位置出现写关键词即拒绝（比服务端"仅查开头"的红线更严） */
    private static final Pattern WRITE_ANYWHERE_RE = Pattern.compile(
            "(?i)\\b(CREATE|MERGE|DELETE|DETACH|SET|REMOVE|DROP|LOAD|FOREACH|INSERT|CALL|USE)\\b");

    /** 只读语句开头白名单 */
    private static final Pattern READ_START_RE = Pattern.compile(
            "(?i)^\\s*(MATCH|OPTIONAL\\s+MATCH|WITH|UNWIND|RETURN)\\b.*");

    private final GraphQueryService queryService;

    public CypherMatchGrader(GraphQueryService queryService) {
        this.queryService = queryService;
    }

    @Override
    public String name() {
        return "cypher_match";
    }

    @Override
    public String description() {
        return "图谱事实核对：执行 expected.cypher 标准查询取数值，与回答中的数字比对，以图谱为标准答案判分";
    }

    @Override
    public GraderScore evaluate(EvalSample sample, AgentRecord record) {
        Map<String, Object> expected = sample.expected();
        Object cypherObj = expected == null ? null : expected.get("cypher");
        if (cypherObj == null || String.valueOf(cypherObj).isBlank()) {
            return GraderScore.fail(name(), "[参数错误] expected.cypher 缺失，无法执行图谱核对");
        }
        String cypher = String.valueOf(cypherObj);
        if (!READ_START_RE.matcher(cypher).matches() || WRITE_ANYWHERE_RE.matcher(cypher).find()) {
            return GraderScore.fail(name(), "[参数错误] expected.cypher 不是只读查询，已拒绝执行");
        }
        if (record.failed()) {
            return GraderScore.fail(name(), "agent 调用失败：" + record.error());
        }

        // 执行标准查询：第一行第一列须为数值
        Object graphValue;
        try {
            List<Map<String, Object>> rows = queryService.query(cypher, null).rows();
            if (rows.isEmpty() || rows.get(0).values().isEmpty()) {
                return GraderScore.fail(name(), "[参数错误] expected.cypher 查询结果为空，无法取数值");
            }
            Object v = rows.get(0).values().iterator().next();
            if (!(v instanceof Number n)) {
                return GraderScore.fail(name(), "[参数错误] expected.cypher 首列非数值：" + v);
            }
            graphValue = n.doubleValue();
        } catch (Exception e) {
            return GraderScore.fail(name(), "[服务错误] 标准查询执行失败：" + e.getMessage());
        }

        double tolerance = expected.get("tolerance") instanceof Number n ? n.doubleValue() : 0.0;
        List<Double> answerNumbers = NumberAccuracyGrader.extractNumbers(record.answer());
        boolean matched = answerNumbers.stream().anyMatch(a -> Math.abs(a - (Double) graphValue) <= tolerance);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("cypher", cypher);
        meta.put("graphValue", graphValue);
        meta.put("answerNumbers", answerNumbers);
        String reason = matched
                ? "回答中的数字与图谱标准查询结果一致（" + graphValue + "）"
                : answerNumbers.isEmpty()
                        ? "回答中未提取到数字，图谱标准查询结果为 " + graphValue
                        : "回答中的数字 " + answerNumbers + " 与图谱标准查询结果 " + graphValue + " 不符";
        return GraderScore.of(name(), matched ? 1.0 : 0.0, reason, meta);
    }
}
