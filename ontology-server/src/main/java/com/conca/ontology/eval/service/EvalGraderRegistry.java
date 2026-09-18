package com.conca.ontology.eval.service;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.conca.ontology.eval.Grader;
import com.conca.ontology.eval.graders.custom.CustomLlmGrader;
import com.conca.ontology.eval.JudgeClient;
import com.conca.ontology.eval.model.CustomGraderDefinition;

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
        for (CustomGraderDefinition d : customGraderStore.all()) {
            graders.put(d.name(), new CustomLlmGrader(judgeClient, d));
        }
        log.info("EvalGraderRegistry initialized with {} graders", graders.size());
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
