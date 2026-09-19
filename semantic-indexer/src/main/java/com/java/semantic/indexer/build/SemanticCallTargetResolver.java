package com.java.semantic.indexer.build;

import com.java.semantic.model.codefact.MethodTarget;
import com.java.semantic.model.index.SemanticAnalysisEvidence;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.semantic.domain.SemanticCall;
import com.java.semantic.semantic.domain.SemanticCallResolution;
import com.java.semantic.semantic.domain.SemanticLocation;
import com.java.semantic.semantic.domain.SemanticMethod;
import com.java.semantic.semantic.domain.SemanticPosition;
import com.java.semantic.semantic.domain.SemanticRange;
import com.java.semantic.semantic.domain.SemanticResolutionOrigin;
import com.java.semantic.syntax.domain.InvocationTarget;
import com.java.semantic.syntax.domain.SourceMethodMetadata;
import com.java.semantic.syntax.domain.SyntaxInvocation;
import java.util.List;
import java.util.Optional;

/** Indexer boundary for enriching syntax call evidence with a ready semantic workspace. */
public interface SemanticCallTargetResolver {

    /** Confirms a semantic workspace can classify an owned declaration when no call site is present. */
    void verifySemanticWorkspace(RepositorySnapshot snapshot, SourceMethodMetadata method);

    SemanticCallResolution resolve(RepositorySnapshot snapshot, SourceMethodMetadata caller,
                                   SyntaxInvocation invocation, boolean localTargetExpected);

    SemanticAnalysisEvidence.ResolutionCoverage snapshot();

    /** Test-only syntax projection. It must never be used to create a review-eligible export. */
    static SemanticCallTargetResolver syntaxOnly() {
        return new SyntaxOnlyResolver();
    }

    final class SyntaxOnlyResolver implements SemanticCallTargetResolver {
        @Override
        public void verifySemanticWorkspace(RepositorySnapshot snapshot, SourceMethodMetadata method) {
            // Test-only extraction has no semantic workspace and is not export evidence.
        }

        @Override
        public SemanticCallResolution resolve(RepositorySnapshot snapshot, SourceMethodMetadata caller,
                                              SyntaxInvocation invocation, boolean localTargetExpected) {
            Optional<InvocationTarget> target = invocation.resolvedTarget();
            if (target.isEmpty()) {
                return SemanticCallResolution.unresolved();
            }
            InvocationTarget syntaxTarget = target.orElseThrow();
            MethodTarget callerTarget = caller.analysisTarget().target().orElseThrow();
            SemanticPosition start = new SemanticPosition(caller.declarationLocation().range().start().line(),
                    caller.declarationLocation().range().start().character());
            SemanticPosition end = new SemanticPosition(caller.declarationLocation().range().end().line(),
                    caller.declarationLocation().range().end().character());
            SemanticRange locationRange = new SemanticRange(start, end);
            SemanticMethod semanticTarget = new SemanticMethod(syntaxTarget.packageName(), syntaxTarget.className(),
                    syntaxTarget.methodName(), syntaxTarget.parameterTypes(), "",
                    new SemanticLocation(snapshot.root().resolve(callerTarget.sourceFile()).toUri().toString(),
                            locationRange, locationRange));
            return SemanticCallResolution.resolved(new SemanticCall(Optional.of(semanticTarget), invocation.expression(),
                    List.of(), false, SemanticResolutionOrigin.DEFINITION_FALLBACK));
        }

        @Override
        public SemanticAnalysisEvidence.ResolutionCoverage snapshot() {
            return new SemanticAnalysisEvidence.ResolutionCoverage(0, 0, 0, 0, 0);
        }
    }
}
