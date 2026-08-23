package com.java.semantic.model.codefact;

import com.java.semantic.model.support.ModelValidation;

public record AnnotationFact(String typeName) {

    public AnnotationFact {
        typeName = ModelValidation.requiredText(typeName, "annotation type");
    }
}
