package com.java.semantic.semantic.application;

import com.java.semantic.callgraph.application.SemanticCallGraphBuilder;
import com.java.semantic.callgraph.domain.OutgoingGraphFragment;
import com.java.semantic.config.OutgoingGraphProperties;
import com.java.semantic.identity.MethodTarget;
import com.java.semantic.identity.MethodTargetDiagnosticId;
import com.java.semantic.repository.application.RepositoryApplicationService;
import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;
import com.java.semantic.semantic.domain.JavaSemanticService;
import com.java.semantic.semantic.domain.SemanticBindingAmbiguousException;
import com.java.semantic.semantic.domain.SemanticBindingUnresolvedException;
import com.java.semantic.semantic.domain.SemanticDeclarationAnchor;
import com.java.semantic.semantic.domain.SemanticMethod;
import com.java.semantic.semantic.domain.SemanticRequestTimeoutException;
import com.java.semantic.semantic.domain.SemanticTargetNotFoundException;
import com.java.semantic.syntax.domain.RepositorySyntax;
import com.java.semantic.syntax.domain.SyntaxExtractionService;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** Orchestrates one revision-locked exact outgoing fragment within a single repository snapshot lease. */
@Slf4j
public final class SemanticAnalysisApplicationService {

    private final RepositoryApplicationService repositoryApplicationService;
    private final SyntaxExtractionService syntaxExtractionService;
    private final ExactMethodDeclarationResolver declarationResolver;
    private final JavaSemanticService semanticService;
    private final SemanticCallGraphBuilder builder;
    private final OutgoingGraphProperties outgoingGraphProperties;

    public SemanticAnalysisApplicationService(
            RepositoryApplicationService repositoryApplicationService,
            SyntaxExtractionService syntaxExtractionService,
            ExactMethodDeclarationResolver declarationResolver,
            JavaSemanticService semanticService,
            SemanticCallGraphBuilder builder,
            OutgoingGraphProperties outgoingGraphProperties) {
        this.repositoryApplicationService = Objects.requireNonNull(
                repositoryApplicationService, "repositoryApplicationService is required");
        this.syntaxExtractionService = Objects.requireNonNull(
                syntaxExtractionService, "syntaxExtractionService is required");
        this.declarationResolver = Objects.requireNonNull(
                declarationResolver, "declarationResolver is required");
        this.semanticService = Objects.requireNonNull(semanticService, "semanticService is required");
        this.builder = Objects.requireNonNull(builder, "builder is required");
        this.outgoingGraphProperties = Objects.requireNonNull(
                outgoingGraphProperties, "outgoingGraphProperties is required");
    }

    public OutgoingGraphFragment analyzeOutgoing(
            RepositoryId repositoryId,
            RepositoryRevision expectedRevision,
            MethodTarget target,
            int depth) {
        Objects.requireNonNull(repositoryId, "repositoryId is required");
        Objects.requireNonNull(expectedRevision, "expectedRevision is required");
        Objects.requireNonNull(target, "target is required");
        long startedAt = System.nanoTime();
        String targetId = MethodTargetDiagnosticId.from(target);
        log.info("phase=analysis outcome=started requestId={} repoId={} revision={} targetId={}",
                requestId(), repositoryId.value(), expectedRevision.value(), targetId);
        try {
            OutgoingGraphFragment result = repositoryApplicationService.withSnapshot(
                    repositoryId,
                    Optional.of(expectedRevision),
                    snapshot -> analyzeSnapshot(snapshot, target, depth));
            String outcome = Optional.ofNullable(result)
                    .map(OutgoingGraphFragment::status)
                    .map(Object::toString)
                    .orElse("completed")
                    .toLowerCase(Locale.ROOT);
            logTerminal(outcome, repositoryId, expectedRevision, startedAt);
            return result;
        } catch (SemanticTargetNotFoundException | SemanticBindingUnresolvedException
                 | SemanticBindingAmbiguousException | SemanticRequestTimeoutException exception) {
            log.warn("phase=analysis outcome=failed requestId={} repoId={} revision={} exceptionType={} durationMs={}",
                    requestId(), repositoryId.value(), expectedRevision.value(), exception.getClass().getSimpleName(),
                    elapsedMillis(startedAt));
            throw exception;
        } catch (RuntimeException exception) {
            log.error("phase=analysis outcome=failed requestId={} repoId={} revision={} exceptionType={} durationMs={}",
                    requestId(), repositoryId.value(), expectedRevision.value(), exception.getClass().getSimpleName(),
                    elapsedMillis(startedAt));
            throw exception;
        }
    }

    private OutgoingGraphFragment analyzeSnapshot(
            com.java.semantic.repository.domain.RepositorySnapshot snapshot,
            MethodTarget target,
            int depth) {
        RepositorySyntax syntax = syntaxExtractionService.extract(snapshot.root());
        SemanticDeclarationAnchor anchor = declarationResolver.resolve(syntax, target);
        SemanticMethod root = semanticService.resolveExactMethod(snapshot, anchor);
        return builder.build(
                snapshot,
                syntax,
                target,
                root,
                depth,
                outgoingGraphProperties.depthTwoNodeBudget());
    }

    private long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    private void logTerminal(
            String outcome,
            RepositoryId repositoryId,
            RepositoryRevision expectedRevision,
            long startedAt) {
        if ("partial".equals(outcome)) {
            log.warn("phase=analysis outcome={} requestId={} repoId={} revision={} durationMs={}",
                    outcome, requestId(), repositoryId.value(), expectedRevision.value(), elapsedMillis(startedAt));
            return;
        }
        log.info("phase=analysis outcome={} requestId={} repoId={} revision={} durationMs={}",
                outcome, requestId(), repositoryId.value(), expectedRevision.value(), elapsedMillis(startedAt));
    }

    private String requestId() {
        return Objects.toString(MDC.get("requestId"), "");
    }
}
