package com.java.semantic.callgraph.domain;

import org.springframework.util.Assert;

import java.util.Objects;

public interface ReadPolicy {

    EvidenceVisibility visibilityOfRepository(String repositoryId);

    EvidenceVisibility visibilityOf(TypeId typeId);

    EvidenceVisibility visibilityOf(MethodId methodId);

    default EvidenceVisibility visibilityOfDiscoveredMethod(
            TypeId declaringType,
            String methodName) {
        Objects.requireNonNull(declaringType, "declaringType is required");
        Assert.hasText(methodName, "methodName is required");
        return visibilityOf(declaringType);
    }
}
