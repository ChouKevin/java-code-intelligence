package com.java.semantic.indexer.build;

import com.java.semantic.model.codefact.MethodTarget;
import com.java.semantic.model.codefact.SyntaxPosition;
import com.java.semantic.model.codefact.SyntaxRange;
import com.java.semantic.model.index.SemanticAnalysisEvidence;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.semantic.domain.JavaSemanticService;
import com.java.semantic.semantic.domain.SemanticCallResolution;
import com.java.semantic.semantic.domain.SemanticCallResolutionStatus;
import com.java.semantic.semantic.domain.SemanticCallSite;
import com.java.semantic.semantic.domain.SemanticLocation;
import com.java.semantic.semantic.domain.SemanticMethod;
import com.java.semantic.semantic.domain.SemanticPosition;
import com.java.semantic.semantic.domain.SemanticRange;
import com.java.semantic.semantic.domain.SemanticSourceClassification;
import com.java.semantic.syntax.domain.SourceMethodMetadata;
import com.java.semantic.syntax.domain.SyntaxInvocation;
import java.util.Objects;

/** Uses the adapter-neutral semantic contract to enrich one prepared export. */
public final class JdtLsSemanticCallTargetResolver implements SemanticCallTargetResolver {
    private final JavaSemanticService semanticService;
    private final ResolutionAccounting accounting = new ResolutionAccounting();

    public JdtLsSemanticCallTargetResolver(JavaSemanticService semanticService) {
        this.semanticService = Objects.requireNonNull(semanticService, "semantic service is required");
    }

    @Override
    public void verifySemanticWorkspace(RepositorySnapshot snapshot, SourceMethodMetadata method) {
        SemanticSourceClassification classification = semanticService.classifySource(snapshot, semanticMethod(snapshot, method));
        if (!(classification instanceof SemanticSourceClassification.LocalSource)) {
            throw new IllegalStateException("JDT LS did not classify an owned export declaration as repository-local");
        }
    }

    @Override
    public SemanticCallResolution resolve(RepositorySnapshot snapshot, SourceMethodMetadata caller,
                                          SyntaxInvocation invocation, boolean localTargetExpected) {
        SemanticCallResolution response = semanticService.resolveCallResolutionAt(snapshot, semanticMethod(snapshot, caller),
                new SemanticCallSite(range(invocation.range()), position(invocation.resolutionAnchor())));
        SemanticCallResolution resolution = response.status() == SemanticCallResolutionStatus.RESOLVED
                && response.call().orElseThrow().target().isEmpty()
                ? SemanticCallResolution.unresolved()
                : response;
        accounting.record(resolution);
        return resolution;
    }

    @Override
    public SemanticAnalysisEvidence.ResolutionCoverage snapshot() {
        return accounting.snapshot();
    }

    private static SemanticMethod semanticMethod(RepositorySnapshot snapshot, SourceMethodMetadata caller) {
        MethodTarget target = caller.analysisTarget().target().orElseThrow(
                () -> new IllegalStateException("JDT LS export requires a syntax-proven method target"));
        SyntaxRange declaration = caller.declarationLocation().range();
        SemanticPosition nameStart = position(caller.namePosition());
        SemanticPosition nameEnd = new SemanticPosition(nameStart.line(), nameStart.character() + caller.name().length());
        return new SemanticMethod(target.packageName(), target.className(), target.methodName(), target.parameterTypes(), "",
                new SemanticLocation(snapshot.root().resolve(caller.declarationLocation().sourceFile()).toUri().toString(), range(declaration),
                        new SemanticRange(nameStart, nameEnd)));
    }

    private static SemanticRange range(SyntaxRange range) {
        return new SemanticRange(position(range.start()), position(range.end()));
    }

    private static SemanticPosition position(SyntaxPosition position) {
        return new SemanticPosition(position.line(), position.character());
    }

    private static final class ResolutionAccounting {
        private long attempted;
        private long resolved;
        private long unresolved;
        private long ambiguous;
        private long external;

        private void record(SemanticCallResolution resolution) {
            attempted++;
            if (resolution.status() == SemanticCallResolutionStatus.RESOLVED) {
                resolved++;
                if (resolution.call().orElseThrow().external()) {
                    external++;
                }
            } else if (resolution.status() == SemanticCallResolutionStatus.UNRESOLVED) {
                unresolved++;
            } else {
                ambiguous++;
            }
        }

        private SemanticAnalysisEvidence.ResolutionCoverage snapshot() {
            return new SemanticAnalysisEvidence.ResolutionCoverage(attempted, resolved, unresolved, ambiguous, external);
        }
    }
}
