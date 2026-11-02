package com.conca.ontology.eval.graders;

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
