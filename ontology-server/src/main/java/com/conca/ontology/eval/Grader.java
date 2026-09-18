package com.conca.ontology.eval;

import com.conca.ontology.eval.model.GraderInput;
import com.conca.ontology.eval.model.GraderScore;

public interface Grader {

    String name();

    String description();

    default String type() {
        return "rule";
    }

    GraderScore evaluate(GraderInput input);
}
