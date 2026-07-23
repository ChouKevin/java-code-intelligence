package com.java.semantic.semantic.application;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.java.semantic.callgraph.application.IncomingSemanticCallGraphBuilder;
import com.java.semantic.callgraph.application.SemanticCallGraphBuilder;
import com.java.semantic.callgraph.domain.IncomingGraphFragment;
import com.java.semantic.callgraph.domain.GraphAnalysisStatus;
import com.java.semantic.callgraph.domain.OutgoingGraphFragment;
import com.java.semantic.config.IncomingGraphProperties;
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
        IncomingSemanticCallGraphBuilder incomingBuilder = mock(IncomingSemanticCallGraphBuilder.class);
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
                    repositories, syntax, resolver, semantic, builder, incomingBuilder,
                    new OutgoingGraphProperties(7), new IncomingGraphProperties(11))
                    .analyzeOutgoing(repositoryId, revision, target, 2);

            List<String> messages = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
            assertThat(messages).anyMatch(message -> message.contains("phase=analysis")
                    && message.contains("outcome=started")
                    && message.contains("direction=outgoing")
                    && message.contains("repoId=orders")
                    && message.contains("targetId=sha256:"));
            assertThat(messages).anyMatch(message -> message.contains("phase=analysis")
                    && message.contains("outcome=success")
                    && message.contains("direction=outgoing")
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
                    .filter(event -> event.getFormattedMessage().contains("phase=analysis")
                            && event.getFormattedMessage().contains("outcome=failed"))
                    .toList();
            assertThat(failures).hasSize(3).allSatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage())
                        .contains("direction=outgoing", "exceptionType=")
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
        IncomingSemanticCallGraphBuilder incomingBuilder = mock(IncomingSemanticCallGraphBuilder.class);
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
                    repositories, syntax, resolver, semantic, builder, incomingBuilder,
                    new OutgoingGraphProperties(7), new IncomingGraphProperties(11))
                    .analyzeOutgoing(repositoryId, revision, target, 2);

            List<ILoggingEvent> partialEvents = appender.list.stream()
                    .filter(event -> event.getFormattedMessage().contains("phase=analysis")
                            && event.getFormattedMessage().contains("outcome=partial"))
                    .toList();
            assertThat(partialEvents).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage()).contains("direction=outgoing");
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void should_log_incoming_success_and_partial_events_with_direction() {
        RepositoryId repositoryId = RepositoryId.of("orders");
        RepositoryRevision revision = RepositoryRevision.fixture();
        MethodTarget target = new MethodTarget("OrderService.java", "com.acme", "OrderService", "place", List.of());
        RepositorySnapshot snapshot = new RepositorySnapshot(repositoryId, Path.of("safe-root"), revision);
        RepositoryApplicationService repositories = mock(RepositoryApplicationService.class);
        SyntaxExtractionService syntax = mock(SyntaxExtractionService.class);
        ExactMethodDeclarationResolver resolver = mock(ExactMethodDeclarationResolver.class);
        JavaSemanticService semantic = mock(JavaSemanticService.class);
        SemanticCallGraphBuilder outgoingBuilder = mock(SemanticCallGraphBuilder.class);
        IncomingSemanticCallGraphBuilder incomingBuilder = mock(IncomingSemanticCallGraphBuilder.class);
        IncomingGraphFragment success = mock(IncomingGraphFragment.class);
        IncomingGraphFragment partial = mock(IncomingGraphFragment.class);
        when(success.status()).thenReturn(GraphAnalysisStatus.SUCCESS);
        when(partial.status()).thenReturn(GraphAnalysisStatus.PARTIAL);
        Logger logger = (Logger) LoggerFactory.getLogger(SemanticAnalysisApplicationService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            when(repositories.withSnapshot(eq(repositoryId), eq(Optional.of(revision)), any())).thenAnswer(invocation -> {
                Function<RepositorySnapshot, IncomingGraphFragment> operation = invocation.getArgument(2);
                return operation.apply(snapshot);
            });
            RepositorySyntax repositorySyntax = RepositorySyntax.empty();
            when(syntax.extract(snapshot.root())).thenReturn(repositorySyntax);
            when(resolver.resolve(repositorySyntax, target)).thenReturn(new SemanticDeclarationAnchor(
                    target, new SemanticPosition(0, 0)));
            when(semantic.resolveExactMethod(eq(snapshot), any())).thenReturn(mock());
            when(incomingBuilder.build(any(), any(), any(), any(), eq(2), eq(11)))
                    .thenReturn(success, partial);
            SemanticAnalysisApplicationService service = new SemanticAnalysisApplicationService(
                    repositories, syntax, resolver, semantic, outgoingBuilder, incomingBuilder,
                    new OutgoingGraphProperties(7), new IncomingGraphProperties(11));

            service.analyzeIncoming(repositoryId, revision, target, 2);
            service.analyzeIncoming(repositoryId, revision, target, 2);

            List<ILoggingEvent> terminalEvents = appender.list.stream()
                    .filter(event -> event.getFormattedMessage().contains("phase=analysis"))
                    .filter(event -> event.getFormattedMessage().contains("durationMs="))
                    .toList();
            assertThat(terminalEvents).hasSize(2).allSatisfy(event ->
                    assertThat(event.getFormattedMessage()).contains("direction=incoming"));
            assertThat(terminalEvents).anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.INFO);
                assertThat(event.getFormattedMessage()).contains("outcome=success");
            });
            assertThat(terminalEvents).anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage()).contains("outcome=partial");
            });
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void should_log_incoming_expected_failures_and_unexpected_failures_without_sensitive_details() {
        RepositoryId repositoryId = RepositoryId.of("orders");
        RepositoryRevision revision = RepositoryRevision.fixture();
        MethodTarget target = new MethodTarget("OrderService.java", "com.acme", "OrderService", "place", List.of());
        MethodTarget restrictedCandidate = new MethodTarget(
                "RESTRICTED_SEMANTIC_CANDIDATE_SENTINEL.java", "com.acme", "OrderService", "place", List.of());
        Logger logger = (Logger) LoggerFactory.getLogger(SemanticAnalysisApplicationService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            RepositoryApplicationService expectedFailureRepositories = mock(RepositoryApplicationService.class);
            SemanticBindingUnresolvedException expectedFailure = new SemanticBindingUnresolvedException(restrictedCandidate);
            when(expectedFailureRepositories.withSnapshot(eq(repositoryId), eq(Optional.of(revision)), any()))
                    .thenThrow(expectedFailure);

            assertThatThrownBy(() -> service(expectedFailureRepositories)
                    .analyzeIncoming(repositoryId, revision, target, 2)).isSameAs(expectedFailure);

            for (Direction direction : Direction.values()) {
                RepositoryApplicationService unexpectedFailureRepositories = mock(RepositoryApplicationService.class);
                IllegalStateException unexpectedFailure = new IllegalStateException(
                        "UNEXPECTED_MESSAGE_SENTINEL /untrusted/path");
                when(unexpectedFailureRepositories.withSnapshot(eq(repositoryId), eq(Optional.of(revision)), any()))
                        .thenThrow(unexpectedFailure);

                assertThatThrownBy(() -> analyze(service(unexpectedFailureRepositories), direction, repositoryId, revision, target))
                        .isSameAs(unexpectedFailure);
            }

            List<ILoggingEvent> failures = appender.list.stream()
                    .filter(event -> event.getFormattedMessage().contains("phase=analysis")
                            && event.getFormattedMessage().contains("outcome=failed"))
                    .toList();
            assertThat(failures).hasSize(3);
            assertThat(failures).anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage()).contains("direction=incoming", "SemanticBindingUnresolvedException")
                        .doesNotContain("RESTRICTED_SEMANTIC_CANDIDATE_SENTINEL");
                assertThat(event.getThrowableProxy()).isNull();
            });
            assertThat(failures).filteredOn(event -> event.getFormattedMessage().contains("IllegalStateException"))
                    .hasSize(2)
                    .allSatisfy(event -> {
                        assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                        assertThat(event.getFormattedMessage())
                                .contains("exceptionType=IllegalStateException")
                                .doesNotContain("UNEXPECTED_MESSAGE_SENTINEL", "/untrusted/path");
                        assertThat(event.getThrowableProxy()).isNull();
                    });
            assertThat(failures).extracting(ILoggingEvent::getFormattedMessage)
                    .anyMatch(message -> message.contains("direction=outgoing"))
                    .anyMatch(message -> message.contains("direction=incoming"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private void analyze(
            SemanticAnalysisApplicationService service,
            Direction direction,
            RepositoryId repositoryId,
            RepositoryRevision revision,
            MethodTarget target) {
        if (Direction.OUTGOING.equals(direction)) {
            service.analyzeOutgoing(repositoryId, revision, target, 2);
            return;
        }
        service.analyzeIncoming(repositoryId, revision, target, 2);
    }

    private SemanticAnalysisApplicationService service(RepositoryApplicationService repositories) {
        return new SemanticAnalysisApplicationService(
                repositories,
                mock(SyntaxExtractionService.class),
                mock(ExactMethodDeclarationResolver.class),
                mock(JavaSemanticService.class),
                mock(SemanticCallGraphBuilder.class),
                mock(IncomingSemanticCallGraphBuilder.class),
                new OutgoingGraphProperties(7),
                new IncomingGraphProperties(11));
    }

    private enum Direction {
        OUTGOING,
        INCOMING
    }
}
