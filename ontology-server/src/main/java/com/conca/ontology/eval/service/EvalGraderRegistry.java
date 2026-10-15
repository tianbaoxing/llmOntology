package com.conca.ontology.eval.service;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.conca.ontology.eval.Grader;
import com.conca.ontology.eval.graders.custom.CustomLlmGrader;
import com.conca.ontology.eval.JudgeClient;
import com.conca.ontology.eval.model.CustomGraderDefinition;

/**
 * @author tianbaoxing
 * @date 2026-09-18
 */
@Component
public class EvalGraderRegistry {

    private static final Logger log = LoggerFactory.getLogger(EvalGraderRegistry.class);

    private final Map<String, Grader> graders = new TreeMap<>();
    private final JudgeClient judgeClient;
    private final CustomGraderStore customGraderStore;

    public EvalGraderRegistry(List<Grader> graderBeans, JudgeClient judgeClient,
            CustomGraderStore customGraderStore) {
        this.judgeClient = judgeClient;
        this.customGraderStore = customGraderStore;
        for (Grader g : graderBeans) {
            graders.put(g.name(), g);
        }
        log.info("EvalGraderRegistry initialized with {} built-in graders", graders.size());
    }

    /**
     * 自定义评测器注册延迟到 ApplicationReadyEvent：
     * 确保在所有 Bean 初始化与启动步骤完成后读表，避免构造期早读 custom_grader 表。
     */
    @EventListener(ApplicationReadyEvent.class)
    void registerCustomGraders() {
        int count = 0;
        for (CustomGraderDefinition d : customGraderStore.all()) {
            graders.put(d.name(), new CustomLlmGrader(judgeClient, d));
            count++;
        }
        log.info("registered {} custom graders from custom_grader table", count);
    }

    public Map<String, Grader> graders() {
        return graders;
    }

    public Grader get(String name) {
        return graders.get(name);
    }

    public boolean containsKey(String name) {
        return graders.containsKey(name);
    }

    public void registerCustom(CustomGraderDefinition def) {
        graders.put(def.name(), new CustomLlmGrader(judgeClient, def));
    }

    public void replaceCustom(String name, CustomGraderDefinition def) {
        graders.put(name, new CustomLlmGrader(judgeClient, def));
    }

    public void removeCustom(String name) {
        graders.remove(name);
    }

    public CustomGraderStore customGraderStore() {
        return customGraderStore;
    }
}
