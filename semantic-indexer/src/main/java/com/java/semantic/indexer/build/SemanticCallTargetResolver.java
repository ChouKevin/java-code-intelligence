package com.java.semantic.indexer.build;

import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.syntax.domain.InvocationTarget;
import com.java.semantic.syntax.domain.SourceMethodMetadata;
import com.java.semantic.syntax.domain.SyntaxInvocation;
import java.util.Optional;

/** Indexer boundary for enriching syntax call evidence with a ready semantic workspace. */
public interface SemanticCallTargetResolver {

    default void beginExport() {
        // Syntax-only projection does not account for semantic resolution.
    }

    /** Confirms the semantic workspace can classify an owned declaration even when a source has no call sites. */
    default void verifySemanticWorkspace(RepositorySnapshot snapshot, SourceMethodMetadata method) {
        // Syntax-only projection deliberately has no semantic workspace.
    }

    Optional<InvocationTarget> resolve(RepositorySnapshot snapshot, SourceMethodMetadata caller, SyntaxInvocation invocation);

    /** Resolves a call while identifying whether syntax already proves that its target belongs to this repository. */
    default Optional<InvocationTarget> resolve(RepositorySnapshot snapshot, SourceMethodMetadata caller,
                                               SyntaxInvocation invocation, boolean localTargetExpected) {
        return resolve(snapshot, caller, invocation);
    }

    default void requireSemanticResolution() {
        // Syntax-only projection tests deliberately use the no-op resolver.
    }

    default void endExport() {
        // Syntax-only projection has no per-export state to release.
    }

    static SemanticCallTargetResolver syntaxOnly() {
        return (snapshot, caller, invocation) -> invocation.resolvedTarget();
    }
}
