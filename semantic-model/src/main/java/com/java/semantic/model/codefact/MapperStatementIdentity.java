package com.java.semantic.model.codefact;

import com.java.semantic.model.support.ModelValidation;

public record MapperStatementIdentity(String namespace, String statementId, String resourcePath) implements CanonicalIdentity {

    public MapperStatementIdentity {
        namespace = ModelValidation.requiredText(namespace, "mapper namespace");
        statementId = ModelValidation.requiredText(statementId, "mapper statement id");
        resourcePath = ModelValidation.repositoryRelativePath(resourcePath);
    }

    @Override
    public String canonicalForm() {
        return namespace + "." + statementId + "@" + resourcePath;
    }
}
