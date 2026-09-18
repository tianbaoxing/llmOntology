package com.conca.ontology.eval.graders.human;

import java.util.Map;

import org.springframework.stereotype.Component;

import com.conca.ontology.eval.Grader;
import com.conca.ontology.eval.model.GraderInput;
import com.conca.ontology.eval.model.GraderScore;

/**
 * 人工评测器（type=human）：评测任务本身不判分，仅占位标记「待人工评分」——
 * 任务结束后由登录用户在页面逐样本打分（1-5 星，可选评语，可跳过），
 * 打分写回结果 JSON 并重算汇总指标。占位分带 metadata.pending=true，
 * summarize / rowStats / Run Diff 均跳过未评分样本（不计入均值、不参与翻转）。
 */
@Component
public class HumanReviewGrader implements Grader {

    @Override
    public String name() {
        return "human_review";
    }

    @Override
    public String description() {
        return "人工评分：任务完成后由人在页面逐样本打分（1-5 星，可选评语，可跳过），非自动判分，适合机器难以客观评判的回答质量";
    }

    @Override
    public String type() {
        return "human";
    }

    @Override
    public GraderScore evaluate(GraderInput input) {
        // 占位分：score=0 仅为占位，pending 标记表示未评分（统计与展示均按待评处理）
        return new GraderScore(name(), 0.0, "待人工评分", false, Map.of("pending", true));
    }
}
