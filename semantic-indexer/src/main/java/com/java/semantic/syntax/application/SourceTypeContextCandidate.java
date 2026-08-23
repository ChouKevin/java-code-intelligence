package com.java.semantic.syntax.application;

import com.java.semantic.identity.RepositoryRelativeSource;

/** type ambiguity 的 repository source selector evidence */
public record SourceTypeContextCandidate(String sourceFile) implements SourceContextCandidate {

    public SourceTypeContextCandidate {
        sourceFile = RepositoryRelativeSource.requireValid(sourceFile);
    }

    @Override
    public SourceSymbolKind kind() {
        return SourceSymbolKind.SOURCE_TYPE;
    }
}
