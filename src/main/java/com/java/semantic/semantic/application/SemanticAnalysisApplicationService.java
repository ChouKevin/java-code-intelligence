package com.java.semantic.semantic.application;

import com.java.semantic.callgraph.application.IncomingSemanticCallGraphBuilder;
import com.java.semantic.callgraph.application.SemanticCallGraphBuilder;
import com.java.semantic.callgraph.domain.GraphAnalysisStatus;
import com.java.semantic.callgraph.domain.IncomingGraphFragment;
import com.java.semantic.callgraph.domain.OutgoingGraphFragment;
import com.java.semantic.config.IncomingGraphProperties;
import com.java.semantic.config.OutgoingGraphProperties;
import com.java.semantic.diagnostic.ExpectedFailure;
import com.java.semantic.identity.MethodTarget;
import com.java.semantic.identity.MethodTargetDiagnosticId;
import com.java.semantic.repository.application.RepositoryApplicationService;
import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.semantic.domain.JavaSemanticService;
import com.java.semantic.semantic.domain.SemanticBindingAmbiguousException;
import com.java.semantic.semantic.domain.SemanticDeclarationAnchor;
import com.java.semantic.semantic.domain.SemanticMethod;
import com.java.semantic.semantic.domain.SemanticPosition;
import com.java.semantic.semantic.domain.SemanticTargetNotFoundException;
import com.java.semantic.syntax.domain.CanonicalMethodDeclarationResolver;
import com.java.semantic.syntax.domain.MethodTargetResolution;
import com.java.semantic.syntax.domain.RepositorySyntax;
import com.java.semantic.syntax.domain.SyntaxExtractionService;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** Orchestrates revision-locked exact call graph fragments within a single repository snapshot lease. */
@Slf4j
public final class SemanticAnalysisApplicationService {

    private final RepositoryApplicationService repositoryApplicationService;
    private final SyntaxExtractionService syntaxExtractionService;
    private final CanonicalMethodDeclarationResolver declarationResolver;
    private final JavaSemanticService semanticService;
    private final SemanticCallGraphBuilder outgoingBuilder;
    private final IncomingSemanticCallGraphBuilder incomingBuilder;
    private final OutgoingGraphProperties outgoingGraphProperties;
    private final IncomingGraphProperties incomingGraphProperties;

    public SemanticAnalysisApplicationService(
            RepositoryApplicationService repositoryApplicationService,
            SyntaxExtractionService syntaxExtractionService,
            CanonicalMethodDeclarationResolver declarationResolver,
            JavaSemanticService semanticService,
            SemanticCallGraphBuilder outgoingBuilder,
            IncomingSemanticCallGraphBuilder incomingBuilder,
            OutgoingGraphProperties outgoingGraphProperties,
            IncomingGraphProperties incomingGraphProperties) {
        this.repositoryApplicationService = Objects.requireNonNull(
                repositoryApplicationService, "repositoryApplicationService is required");
        this.syntaxExtractionService = Objects.requireNonNull(
                syntaxExtractionService, "syntaxExtractionService is required");
        this.declarationResolver = Objects.requireNonNull(
                declarationResolver, "declarationResolver is required");
        this.semanticService = Objects.requireNonNull(semanticService, "semanticService is required");
        this.outgoingBuilder = Objects.requireNonNull(outgoingBuilder, "outgoingBuilder is required");
        this.incomingBuilder = Objects.requireNonNull(incomingBuilder, "incomingBuilder is required");
        this.outgoingGraphProperties = Objects.requireNonNull(
                outgoingGraphProperties, "outgoingGraphProperties is required");
        this.incomingGraphProperties = Objects.requireNonNull(
                incomingGraphProperties, "incomingGraphProperties is required");
    }

    public OutgoingGraphFragment analyzeOutgoing(
            RepositoryId repositoryId,
            RepositoryRevision expectedRevision,
            MethodTarget target,
            int depth) {
        return analyze(
                AnalysisDirection.OUTGOING,
                repositoryId,
                expectedRevision,
                target,
                depth,
                outgoingGraphProperties.depthTwoNodeBudget(),
                outgoingBuilder::build,
                OutgoingGraphFragment::status);
    }

    public IncomingGraphFragment analyzeIncoming(
            RepositoryId repositoryId,
            RepositoryRevision expectedRevision,
            MethodTarget target,
            int depth) {
        return analyze(
                AnalysisDirection.INCOMING,
                repositoryId,
                expectedRevision,
                target,
                depth,
                incomingGraphProperties.depthTwoNodeBudget(),
                incomingBuilder::build,
                IncomingGraphFragment::status);
    }

    private <T> T analyze(
            AnalysisDirection direction,
            RepositoryId repositoryId,
            RepositoryRevision expectedRevision,
            MethodTarget target,
            int depth,
            int depthTwoNodeBudget,
            GraphFragmentBuilder<T> graphBuilder,
            GraphFragmentStatus<T> fragmentStatus) {
        Objects.requireNonNull(direction, "direction is required");
        Objects.requireNonNull(repositoryId, "repositoryId is required");
        Objects.requireNonNull(expectedRevision, "expectedRevision is required");
        Objects.requireNonNull(target, "target is required");
        Objects.requireNonNull(graphBuilder, "graphBuilder is required");
        Objects.requireNonNull(fragmentStatus, "fragmentStatus is required");
        long startedAt = System.nanoTime();
        String targetId = MethodTargetDiagnosticId.from(target);
        log.info("phase=analysis direction={} outcome=started requestId={} repoId={} revision={} targetId={}",
                direction.value(), requestId(), repositoryId.value(), expectedRevision.value(), targetId);
        try {
            T result = repositoryApplicationService.withSnapshot(
                    repositoryId,
                    Optional.of(expectedRevision),
                    snapshot -> analyzeSnapshot(snapshot, target, depth, depthTwoNodeBudget, graphBuilder));
            String outcome = Optional.ofNullable(result)
                    .map(fragmentStatus::status)
                    .map(Object::toString)
                    .orElse("completed")
                    .toLowerCase(Locale.ROOT);
            logTerminal(direction, outcome, repositoryId, expectedRevision, startedAt);
            return result;
        } catch (RuntimeException exception) {
            logFailure(direction, repositoryId, expectedRevision, startedAt, exception);
            throw exception;
        }
    }

    private <T> T analyzeSnapshot(
            RepositorySnapshot snapshot,
            MethodTarget target,
            int depth,
            int depthTwoNodeBudget,
            GraphFragmentBuilder<T> graphBuilder) {
        RepositorySyntax syntax = syntaxExtractionService.extract(snapshot.root());
        MethodTargetResolution resolution = declarationResolver.resolve(syntax, target);
        SemanticDeclarationAnchor anchor = declarationAnchor(syntax, target, resolution);
        SemanticMethod root = semanticService.resolveExactMethod(snapshot, anchor);
        return graphBuilder.build(snapshot, syntax, target, root, depth, depthTwoNodeBudget);
    }

    private SemanticDeclarationAnchor declarationAnchor(
            RepositorySyntax syntax,
            MethodTarget target,
            MethodTargetResolution resolution) {
        return switch (resolution.status()) {
            case RESOLVED -> syntax.classes().stream()
                    .flatMap(metadata -> metadata.methods().stream())
                    .filter(method -> method.analysisTarget().target().filter(target::equals).isPresent())
                    .map(method -> new SemanticDeclarationAnchor(
                            target,
                            new SemanticPosition(method.namePosition().line(), method.namePosition().character())))
                    .findFirst()
                    .orElseThrow(() -> new SemanticTargetNotFoundException(target));
            case UNRESOLVED -> throw new SemanticTargetNotFoundException(target);
            case AMBIGUOUS -> throw new SemanticBindingAmbiguousException(target, resolution.candidates());
        };
    }

    private long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    private void logTerminal(
            AnalysisDirection direction,
            String outcome,
            RepositoryId repositoryId,
            RepositoryRevision expectedRevision,
            long startedAt) {
        if ("partial".equals(outcome)) {
            log.warn("phase=analysis direction={} outcome={} requestId={} repoId={} revision={} durationMs={}",
                    direction.value(), outcome, requestId(), repositoryId.value(), expectedRevision.value(), elapsedMillis(startedAt));
            return;
        }
        log.info("phase=analysis direction={} outcome={} requestId={} repoId={} revision={} durationMs={}",
                direction.value(), outcome, requestId(), repositoryId.value(), expectedRevision.value(), elapsedMillis(startedAt));
    }

    private void logFailure(
            AnalysisDirection direction,
            RepositoryId repositoryId,
            RepositoryRevision expectedRevision,
            long startedAt,
            RuntimeException exception) {
        if (exception instanceof ExpectedFailure) {
            log.warn("phase=analysis direction={} outcome=failed requestId={} repoId={} revision={} exceptionType={} durationMs={}",
                    direction.value(), requestId(), repositoryId.value(), expectedRevision.value(),
                    exception.getClass().getSimpleName(), elapsedMillis(startedAt));
            return;
        }
        log.error("phase=analysis direction={} outcome=failed requestId={} repoId={} revision={} exceptionType={} durationMs={}",
                direction.value(), requestId(), repositoryId.value(), expectedRevision.value(),
                exception.getClass().getSimpleName(), elapsedMillis(startedAt));
    }

    private String requestId() {
        return Objects.toString(MDC.get("requestId"), "");
    }

    private enum AnalysisDirection {
        OUTGOING("outgoing"),
        INCOMING("incoming");

        private final String value;

        AnalysisDirection(String value) {
            this.value = value;
        }

        private String value() {
            return value;
        }
    }

    @FunctionalInterface
    private interface GraphFragmentBuilder<T> {

        T build(
                RepositorySnapshot snapshot,
                RepositorySyntax syntax,
                MethodTarget rootTarget,
                SemanticMethod root,
                int requestedDepth,
                int depthTwoNodeBudget);
    }

    @FunctionalInterface
    private interface GraphFragmentStatus<T> {

        GraphAnalysisStatus status(T fragment);
    }
}
