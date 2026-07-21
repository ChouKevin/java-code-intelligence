package com.java.semantic.callgraph.domain;

public interface ReadPolicy {

    EvidenceVisibility visibilityOfRepository(String repositoryId);

    EvidenceVisibility visibilityOf(TypeId typeId);

    EvidenceVisibility visibilityOf(MethodId methodId);
}
