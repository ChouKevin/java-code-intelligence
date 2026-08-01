package com.java.semantic.syntax.application;

import com.java.semantic.repository.domain.RepositorySnapshot;

/** repository snapshot 內 source-only symbol resolution port */
public interface SourceSymbolResolver {

    SourceSymbolResolution resolve(RepositorySnapshot snapshot, SourceSymbolResolutionQuery query);
}
