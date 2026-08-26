package com.java.semantic.indexer.build;

import com.java.semantic.model.codefact.MethodTarget;
import com.java.semantic.model.codefact.SyntaxPosition;
import com.java.semantic.model.codefact.SyntaxRange;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.semantic.domain.JavaSemanticService;
import com.java.semantic.semantic.domain.SemanticCall;
import com.java.semantic.semantic.domain.SemanticCallResolution;
import com.java.semantic.semantic.domain.SemanticCallResolutionStatus;
import com.java.semantic.semantic.domain.SemanticCallSite;
import com.java.semantic.semantic.domain.SemanticLocation;
import com.java.semantic.semantic.domain.SemanticMethod;
import com.java.semantic.semantic.domain.SemanticPosition;
import com.java.semantic.semantic.domain.SemanticRange;
import com.java.semantic.semantic.domain.SemanticSourceClassification;
import com.java.semantic.syntax.domain.InvocationTarget;
import com.java.semantic.syntax.domain.SourceMethodMetadata;
import com.java.semantic.syntax.domain.SyntaxInvocation;
import java.util.Optional;

/** Uses the adapter-neutral semantic contract to enrich Indexer call projections. */
public final class JdtLsSemanticCallTargetResolver implements SemanticCallTargetResolver {
    private final JavaSemanticService semanticService;
    private final ThreadLocal<ResolutionAccounting> accounting = ThreadLocal.withInitial(ResolutionAccounting::new);

    public JdtLsSemanticCallTargetResolver(JavaSemanticService semanticService) {
        this.semanticService = java.util.Objects.requireNonNull(semanticService, "semantic service is required");
    }

    @Override
    public void beginExport() {
        accounting.set(new ResolutionAccounting());
    }

    @Override
    public void verifySemanticWorkspace(RepositorySnapshot snapshot, SourceMethodMetadata method) {
        SemanticSourceClassification classification = semanticService.classifySource(snapshot, semanticMethod(snapshot, method));
        if (!(classification instanceof SemanticSourceClassification.LocalSource)) {
            throw new IllegalStateException("JDT LS did not classify an owned export declaration as repository-local");
        }
    }

    @Override
    public Optional<InvocationTarget> resolve(RepositorySnapshot snapshot, SourceMethodMetadata caller, SyntaxInvocation invocation) {
        return resolve(snapshot, caller, invocation, false);
    }

    @Override
    public Optional<InvocationTarget> resolve(RepositorySnapshot snapshot, SourceMethodMetadata caller,
                                              SyntaxInvocation invocation, boolean localTargetExpected) {
        if (localTargetExpected) {
            accounting.get().expectedLocal++;
        }
        SemanticCallResolution resolution = semanticService.resolveCallResolutionAt(snapshot, semanticMethod(snapshot, caller),
                new SemanticCallSite(range(invocation.range()), position(invocation.resolutionAnchor())));
        if (resolution.status() != SemanticCallResolutionStatus.RESOLVED) {
            return invocation.resolvedTarget();
        }
        SemanticCall call = resolution.call().orElseThrow();
        Optional<InvocationTarget> resolved = call.target().map(target -> new InvocationTarget(target.packageName(),
                target.className(), target.methodName(), target.parameterTypes()));
        if (resolved.isPresent()) {
            if (localTargetExpected) {
                accounting.get().resolvedLocal++;
            }
            return resolved;
        }
        return invocation.resolvedTarget();
    }

    @Override
    public void requireSemanticResolution() {
        ResolutionAccounting exportAccounting = accounting.get();
        if (exportAccounting.expectedLocal > 0 && exportAccounting.resolvedLocal == 0) {
            throw new IllegalStateException("JDT LS did not resolve any export call site");
        }
    }

    @Override
    public void endExport() {
        accounting.remove();
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
        private int expectedLocal;
        private int resolvedLocal;
    }
}
