package com.java.semantic.semantic.application;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.java.semantic.callgraph.application.SemanticCallGraphBuilder;
import com.java.semantic.callgraph.domain.GraphAnalysisStatus;
import com.java.semantic.callgraph.domain.OutgoingGraphFragment;
import com.java.semantic.config.OutgoingGraphProperties;
import com.java.semantic.identity.MethodTarget;
import com.java.semantic.repository.application.RepositoryApplicationService;
import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.semantic.domain.JavaSemanticService;
import com.java.semantic.semantic.domain.SemanticDeclarationAnchor;
import com.java.semantic.semantic.domain.SemanticBindingAmbiguousException;
import com.java.semantic.semantic.domain.SemanticBindingUnresolvedException;
import com.java.semantic.semantic.domain.SemanticPosition;
import com.java.semantic.semantic.domain.SemanticRequestTimeoutException;
import com.java.semantic.syntax.domain.RepositorySyntax;
import com.java.semantic.syntax.domain.SyntaxExtractionService;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SemanticAnalysisLoggingTest {

    @Test
    void should_log_safe_start_and_terminal_analysis_events() {
        RepositoryId repositoryId = RepositoryId.of("orders");
        RepositoryRevision revision = RepositoryRevision.fixture();
        MethodTarget target = new MethodTarget(
                "src/METHOD_BODY_SENTINEL.java",
                "CREDENTIAL_SENTINEL",
                "CredentialSentinel",
                "methodBodySentinel",
                List.of("METHOD_BODY_PARAMETER_SENTINEL"));
        RepositorySnapshot snapshot = new RepositorySnapshot(repositoryId, Path.of("safe-root"), revision);
        RepositoryApplicationService repositories = mock(RepositoryApplicationService.class);
        SyntaxExtractionService syntax = mock(SyntaxExtractionService.class);
        ExactMethodDeclarationResolver resolver = mock(ExactMethodDeclarationResolver.class);
        JavaSemanticService semantic = mock(JavaSemanticService.class);
        SemanticCallGraphBuilder builder = mock(SemanticCallGraphBuilder.class);
        OutgoingGraphFragment result = mock(OutgoingGraphFragment.class);
        when(result.status()).thenReturn(GraphAnalysisStatus.SUCCESS);
        Logger logger = (Logger) LoggerFactory.getLogger(SemanticAnalysisApplicationService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            when(repositories.withSnapshot(eq(repositoryId), eq(Optional.of(revision)), any())).thenAnswer(invocation -> {
                Function<RepositorySnapshot, OutgoingGraphFragment> operation = invocation.getArgument(2);
                return operation.apply(snapshot);
            });
            RepositorySyntax repositorySyntax = RepositorySyntax.empty();
            when(syntax.extract(snapshot.root())).thenReturn(repositorySyntax);
            when(resolver.resolve(repositorySyntax, target)).thenReturn(new SemanticDeclarationAnchor(
                    target, new SemanticPosition(0, 0)));
            when(semantic.resolveExactMethod(eq(snapshot), any())).thenReturn(mock());
            when(builder.build(any(), any(), any(), any(), eq(2), eq(7))).thenReturn(result);

            new SemanticAnalysisApplicationService(
                    repositories, syntax, resolver, semantic, builder, new OutgoingGraphProperties(7))
                    .analyzeOutgoing(repositoryId, revision, target, 2);

            List<String> messages = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
            assertThat(messages).anyMatch(message -> message.contains("phase=analysis outcome=started")
                    && message.contains("repoId=orders")
                    && message.contains("targetId=sha256:"));
            assertThat(messages).anyMatch(message -> message.contains("phase=analysis outcome=success")
                    && message.contains("durationMs="));
            assertThat(messages).noneMatch(message -> message.contains("sourceFile=")
                    || message.contains("className=")
                    || message.contains("methodName=")
                    || message.contains("METHOD_BODY_SENTINEL")
                    || message.contains("CREDENTIAL_SENTINEL")
                    || message.contains("CredentialSentinel")
                    || message.contains("methodBodySentinel")
                    || message.contains("METHOD_BODY_PARAMETER_SENTINEL")
                    || message.contains("safe-root")
                    || message.contains("X-Api-Token")
                    || message.contains("{\"jsonrpc\""));
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void should_log_expected_semantic_failures_at_warn_without_exception_details() {
        RepositoryId repositoryId = RepositoryId.of("orders");
        RepositoryRevision revision = RepositoryRevision.fixture();
        MethodTarget target = new MethodTarget("OrderService.java", "com.acme", "OrderService", "place", List.of());
        MethodTarget restrictedCandidate = new MethodTarget(
                "RESTRICTED_SEMANTIC_CANDIDATE_SENTINEL.java", "com.acme", "OrderService", "place", List.of());
        List<RuntimeException> expectedFailures = List.of(
                new SemanticBindingAmbiguousException(target, List.of(target, restrictedCandidate)),
                new SemanticBindingUnresolvedException(restrictedCandidate),
                new SemanticRequestTimeoutException());
        Logger logger = (Logger) LoggerFactory.getLogger(SemanticAnalysisApplicationService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            for (RuntimeException expectedFailure : expectedFailures) {
                RepositoryApplicationService repositories = mock(RepositoryApplicationService.class);
                when(repositories.withSnapshot(eq(repositoryId), eq(Optional.of(revision)), any()))
                        .thenThrow(expectedFailure);

                assertThatThrownBy(() -> service(repositories).analyzeOutgoing(repositoryId, revision, target, 2))
                        .isSameAs(expectedFailure);
            }

            List<ILoggingEvent> failures = appender.list.stream()
                    .filter(event -> event.getFormattedMessage().contains("phase=analysis outcome=failed"))
                    .toList();
            assertThat(failures).hasSize(3).allSatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage())
                        .contains("exceptionType=")
                        .doesNotContain("RESTRICTED_SEMANTIC_CANDIDATE_SENTINEL", " at ");
                assertThat(event.getThrowableProxy()).isNull();
            });
            assertThat(failures).extracting(ILoggingEvent::getFormattedMessage)
                    .anyMatch(message -> message.contains("SemanticBindingAmbiguousException"))
                    .anyMatch(message -> message.contains("SemanticBindingUnresolvedException"))
                    .anyMatch(message -> message.contains("SemanticRequestTimeoutException"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void should_log_returned_partial_analysis_summary_at_warn_without_a_throwable() {
        RepositoryId repositoryId = RepositoryId.of("orders");
        RepositoryRevision revision = RepositoryRevision.fixture();
        MethodTarget target = new MethodTarget("OrderService.java", "com.acme", "OrderService", "place", List.of());
        RepositorySnapshot snapshot = new RepositorySnapshot(repositoryId, Path.of("safe-root"), revision);
        RepositoryApplicationService repositories = mock(RepositoryApplicationService.class);
        SyntaxExtractionService syntax = mock(SyntaxExtractionService.class);
        ExactMethodDeclarationResolver resolver = mock(ExactMethodDeclarationResolver.class);
        JavaSemanticService semantic = mock(JavaSemanticService.class);
        SemanticCallGraphBuilder builder = mock(SemanticCallGraphBuilder.class);
        OutgoingGraphFragment result = mock(OutgoingGraphFragment.class);
        when(result.status()).thenReturn(GraphAnalysisStatus.PARTIAL);
        Logger logger = (Logger) LoggerFactory.getLogger(SemanticAnalysisApplicationService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            when(repositories.withSnapshot(eq(repositoryId), eq(Optional.of(revision)), any())).thenAnswer(invocation -> {
                Function<RepositorySnapshot, OutgoingGraphFragment> operation = invocation.getArgument(2);
                return operation.apply(snapshot);
            });
            RepositorySyntax repositorySyntax = RepositorySyntax.empty();
            when(syntax.extract(snapshot.root())).thenReturn(repositorySyntax);
            when(resolver.resolve(repositorySyntax, target)).thenReturn(new SemanticDeclarationAnchor(
                    target, new SemanticPosition(0, 0)));
            when(semantic.resolveExactMethod(eq(snapshot), any())).thenReturn(mock());
            when(builder.build(any(), any(), any(), any(), eq(2), eq(7))).thenReturn(result);

            new SemanticAnalysisApplicationService(
                    repositories, syntax, resolver, semantic, builder, new OutgoingGraphProperties(7))
                    .analyzeOutgoing(repositoryId, revision, target, 2);

            List<ILoggingEvent> partialEvents = appender.list.stream()
                    .filter(event -> event.getFormattedMessage().contains("phase=analysis outcome=partial"))
                    .toList();
            assertThat(partialEvents).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private SemanticAnalysisApplicationService service(RepositoryApplicationService repositories) {
        return new SemanticAnalysisApplicationService(
                repositories,
                mock(SyntaxExtractionService.class),
                mock(ExactMethodDeclarationResolver.class),
                mock(JavaSemanticService.class),
                mock(SemanticCallGraphBuilder.class),
                new OutgoingGraphProperties(7));
    }
}
