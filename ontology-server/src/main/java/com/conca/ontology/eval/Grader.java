package com.conca.ontology.eval;

import com.conca.ontology.eval.model.GraderInput;
import com.conca.ontology.eval.model.GraderScore;

/**
 * @author tianbaoxing
 * @date 2026-09-16
 */
public interface Grader {

    String name();

    String description();

    default String type() {
        return "rule";
    }

    GraderScore evaluate(GraderInput input);
}
