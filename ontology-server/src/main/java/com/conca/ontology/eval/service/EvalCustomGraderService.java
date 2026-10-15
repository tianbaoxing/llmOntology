package com.conca.ontology.eval.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.conca.ontology.eval.Grader;
import com.conca.ontology.eval.JudgeClient;
import com.conca.ontology.eval.dto.request.EvalCustomGraderRequest;
import com.conca.ontology.eval.dto.request.EvalCustomGraderTestRequest;
import com.conca.ontology.eval.graders.custom.CustomLlmGrader;
import com.conca.ontology.eval.model.AgentRecord;
import com.conca.ontology.eval.model.CustomGraderDefinition;
import com.conca.ontology.eval.model.GraderInput;
import com.conca.ontology.eval.model.GraderScore;
import com.conca.ontology.shared.web.ApiException;

/**
 * @author tianbaoxing
 * @date 2026-09-18
 */
@Service
public class EvalCustomGraderService {

    private static final Logger log = LoggerFactory.getLogger(EvalCustomGraderService.class);

    private final EvalGraderRegistry graderRegistry;
    private final JudgeClient judgeClient;
    private final EvalDatasetService datasetService;

    public EvalCustomGraderService(EvalGraderRegistry graderRegistry, JudgeClient judgeClient,
            EvalDatasetService datasetService) {
        this.graderRegistry = graderRegistry;
        this.judgeClient = judgeClient;
        this.datasetService = datasetService;
    }

    public Map<String, Object> graderList() {
        List<Map<String, Object>> list = new ArrayList<>();
        graderRegistry.graders().forEach((name, g) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", g.name());
            m.put("description", g.description());
            m.put("type", g.type());
            m.put("group", groupOf(g));
            list.add(m);
        });
        return Map.of("graders", list);
    }

    private static String groupOf(Grader g) {
        String prefix = Grader.class.getPackageName() + ".graders.";
        String pkg = g.getClass().getPackage().getName();
        return pkg.startsWith(prefix) ? pkg.substring(prefix.length()) : "other";
    }

    public Map<String, Object> customGraderList() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (CustomGraderDefinition d : graderRegistry.customGraderStore().all()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", d.name());
            m.put("description", d.description());
            m.put("mode", d.mode());
            m.put("updatedAt", d.updatedAt());
            list.add(m);
        }
        return Map.of("graders", list);
    }

    public Map<String, Object> customGraderDetail(String name) {
        CustomGraderDefinition d = graderRegistry.customGraderStore().find(name);
        if (d == null) {
            throw new ApiException(404, "自定义评测器不存在: " + name);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", d.name());
        m.put("description", d.description());
        m.put("mode", d.mode());
        m.put("prompt", d.prompt());
        m.put("createdAt", d.createdAt());
        m.put("updatedAt", d.updatedAt());
        return m;
    }

    public synchronized Map<String, Object> createCustomGrader(EvalCustomGraderRequest req) {
        CustomGraderDefinition def = CustomGraderStore.validate(req.name(), req.description(),
                req.mode(), req.prompt(), null);
        if (graderRegistry.containsKey(def.name())) {
            throw new ApiException(400, "评测器名已存在（内置或自定义）: " + def.name() + "，请换一个名称");
        }
        graderRegistry.customGraderStore().create(def);
        graderRegistry.registerCustom(def);
        log.info("custom grader created: {}", def.name());
        return Map.of("name", def.name(), "mode", def.mode());
    }

    public synchronized Map<String, Object> updateCustomGrader(String name, EvalCustomGraderRequest req) {
        if (graderRegistry.customGraderStore().find(name) == null) {
            throw new ApiException(404, "自定义评测器不存在: " + name);
        }
        if (req.name() != null && !req.name().isBlank() && !req.name().trim().equals(name)) {
            throw new ApiException(400, "名称不可修改（当前 " + name + "）");
        }
        CustomGraderDefinition def = CustomGraderStore.validate(name, req.description(),
                req.mode(), req.prompt(), graderRegistry.customGraderStore().find(name).createdAt());
        graderRegistry.customGraderStore().update(name, def);
        graderRegistry.replaceCustom(name, def);
        log.info("custom grader updated: {}", name);
        return Map.of("name", name, "mode", def.mode());
    }

    public synchronized Map<String, Object> deleteCustomGrader(String name) {
        if (graderRegistry.customGraderStore().find(name) == null) {
            throw new ApiException(404, "自定义评测器不存在: " + name);
        }
        List<String> refs = findGraderReferences(name);
        if (!refs.isEmpty()) {
            throw new ApiException(409, "评测器被以下数据集引用，请先清理引用后再删除："
                    + String.join("、", refs));
        }
        graderRegistry.customGraderStore().delete(name);
        graderRegistry.removeCustom(name);
        log.info("custom grader deleted: {}", name);
        return Map.of("name", name);
    }

    public Map<String, Object> testCustomGrader(String name, EvalCustomGraderTestRequest req) {
        Grader g = graderRegistry.get(name);
        if (g == null || !(g instanceof CustomLlmGrader cg)) {
            throw new ApiException(404, "自定义评测器不存在: " + name);
        }
        if (!judgeClient.isConfigured()) {
            throw new ApiException(409, "AI 服务未配置（缺少 OPENROUTER_API_KEY）：试跑依赖 LLM 判分通道");
        }
        AgentRecord record = new AgentRecord(req.response() == null ? "" : req.response(),
                List.of(), 0, 0, 0, 0, null);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("query", req.query());
        params.put("response", req.response());
        params.put("reference_response", req.referenceResponse());
        params.put("rubric", req.rubric());
        GraderScore s = cg.evaluate(new GraderInput(params, record));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("score", s.score());
        m.put("reason", s.reason());
        m.put("error", s.error());
        m.put("metadata", s.metadata());
        return m;
    }

    /** 引用扫描下放数据集域（P6 起查表，替代原目录逐文件 contains 扫描；输出格式不变） */
    private List<String> findGraderReferences(String name) {
        return datasetService.searchGraderReferences(name);
    }
}
