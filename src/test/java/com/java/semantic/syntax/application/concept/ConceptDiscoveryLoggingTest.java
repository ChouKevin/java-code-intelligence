package com.java.semantic.syntax.application.concept;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.java.semantic.repository.application.RepositoryApplicationService;
import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.syntax.domain.RepositorySyntax;
import com.java.semantic.syntax.domain.RevisionBoundRepositorySyntaxProvider;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 驗證概念探索以安全且關聯的單一階段事件記錄各項耗時 */
class ConceptDiscoveryLoggingTest {

    private static final RepositoryId REPOSITORY_ID = RepositoryId.of("orders");
    private static final RepositoryRevision REVISION = RepositoryRevision.ofSha(
            "1111111111111111111111111111111111111111");
    private static final Path REPOSITORY_ROOT = Path.of("/workspace/orders");

    @Test
    void should_calculate_elapsed_time_when_the_monotonic_origin_is_negative() {
        assertThat(ConceptDiscoveryApplicationService.elapsedMillis(-2_000_000, -500_000))
                .isEqualTo(1);
    }

    @Test
    void should_emit_one_failed_phase_event_when_an_inactive_kind_is_rejected() {
        RepositoryApplicationService repositoryApplicationService = mock(RepositoryApplicationService.class);
        RevisionBoundRepositorySyntaxProvider repositorySyntaxProvider = mock(RevisionBoundRepositorySyntaxProvider.class);
        ConceptDiscoveryApplicationService service = new ConceptDiscoveryApplicationService(
                repositoryApplicationService,
                repositorySyntaxProvider,
                loggingProjector(),
                new ConceptSearchDocumentProjector(),
                new ConceptSearchMatcher());
        ConceptSearchQuery query = query(Set.of(ConceptKind.SQL_IDENTIFIER));
        CapturedLogs capturedLogs = captureLogs();
        MDC.put("requestId", "request-42");
        try {
            Throwable thrown = catchThrowable(() -> service.search(query));

            assertThat(thrown).isInstanceOf(ConceptKindUnavailableException.class);
            String phaseMessage = singlePhaseMessage(capturedLogs.events());
            assertThat(phaseMessage)
                    .contains("requestId=request-42")
                    .contains("repositoryRevision=" + REVISION.value())
                    .contains("outcome=FAILED");
            verifyNoInteractions(repositoryApplicationService, repositorySyntaxProvider);
        } finally {
            MDC.remove("requestId");
            capturedLogs.stop();
        }
    }

    @Test
    void should_emit_one_revision_bound_phase_event_with_safe_query_context_and_timings() {
        RepositoryApplicationService repositoryApplicationService = mock(RepositoryApplicationService.class);
        RevisionBoundRepositorySyntaxProvider repositorySyntaxProvider = mock(RevisionBoundRepositorySyntaxProvider.class);
        ConceptDiscoveryApplicationService service = new ConceptDiscoveryApplicationService(
                repositoryApplicationService,
                repositorySyntaxProvider,
                loggingProjector(),
                new ConceptSearchDocumentProjector(),
                new ConceptSearchMatcher());
        ConceptSearchQuery query = new ConceptSearchQuery(
                REPOSITORY_ID,
                REVISION,
                List.of(new ConceptSearchTerm("Order", ConceptMatchMode.TOKEN_PREFIX)),
                Set.of(ConceptKind.METHOD),
                Set.of(),
                4,
                10);
        RepositorySnapshot snapshot = new RepositorySnapshot(REPOSITORY_ID, REPOSITORY_ROOT, REVISION);
        delegateSnapshot(repositoryApplicationService, snapshot, query);
        when(repositorySyntaxProvider.get(any()))
                .thenReturn(new RepositorySyntax(List.of(), List.of(), List.of()));
        CapturedLogs capturedLogs = captureLogs();
        MDC.put("requestId", "request-42");
        try {
            service.search(query);

            List<String> phaseMessages = phaseMessages(capturedLogs.events());
            assertThat(phaseMessages).hasSize(1);
            String phaseMessage = phaseMessages.getFirst();
            assertThat(phaseMessage)
                    .contains("requestId=request-42")
                    .contains("repoId=orders")
                    .contains("repositoryRevision=1111111111111111111111111111111111111111")
                    .contains("matchModes=[TOKEN_PREFIX]")
                    .contains("requestedKinds=[METHOD]")
                    .contains("termCount=1")
                    .contains("normalizedTerms=[order]")
                    .contains("offset=4")
                    .contains("limit=10")
                    .contains("returnedCount=0")
                    .contains("pageTotalCount=0")
                    .contains("issueCount=0")
                    .contains("outcome=COMPLETED")
                    .containsPattern("snapshotAcquireMillis=\\d+")
                    .containsPattern("syntaxExtractionMillis=\\d+")
                    .containsPattern("catalogProjectionMillis=\\d+")
                    .containsPattern("matchingMillis=\\d+");
        } finally {
            MDC.remove("requestId");
            capturedLogs.stop();
        }
    }

    @Test
    void should_render_requested_kinds_in_concept_kind_enum_order() {
        RepositoryApplicationService repositoryApplicationService = mock(RepositoryApplicationService.class);
        RevisionBoundRepositorySyntaxProvider repositorySyntaxProvider = mock(RevisionBoundRepositorySyntaxProvider.class);
        ConceptDiscoveryApplicationService service = new ConceptDiscoveryApplicationService(
                repositoryApplicationService,
                repositorySyntaxProvider,
                loggingProjector(),
                new ConceptSearchDocumentProjector(),
                new ConceptSearchMatcher());
        ConceptSearchQuery query = query(new LinkedHashSet<>(List.of(
                ConceptKind.SCHEDULE,
                ConceptKind.FIELD,
                ConceptKind.TYPE,
                ConceptKind.METHOD)));
        delegateSnapshot(
                repositoryApplicationService,
                new RepositorySnapshot(REPOSITORY_ID, REPOSITORY_ROOT, REVISION),
                query);
        when(repositorySyntaxProvider.get(any()))
                .thenReturn(new RepositorySyntax(List.of(), List.of(), List.of()));
        CapturedLogs capturedLogs = captureLogs();
        try {
            service.search(query);

            assertThat(singlePhaseMessage(capturedLogs.events()))
                    .contains("requestedKinds=[TYPE, METHOD, FIELD, SCHEDULE]");
        } finally {
            capturedLogs.stop();
        }
    }

    @Test
    void should_record_elapsed_extraction_and_unstarted_later_stages_when_extraction_fails() {
        RepositoryApplicationService repositoryApplicationService = mock(RepositoryApplicationService.class);
        RevisionBoundRepositorySyntaxProvider repositorySyntaxProvider = mock(RevisionBoundRepositorySyntaxProvider.class);
        ConceptDiscoveryApplicationService service = new ConceptDiscoveryApplicationService(
                repositoryApplicationService,
                repositorySyntaxProvider,
                loggingProjector(),
                new ConceptSearchDocumentProjector(),
                new ConceptSearchMatcher());
        ConceptSearchQuery query = query(Set.of(ConceptKind.METHOD));
        delegateSnapshot(
                repositoryApplicationService,
                new RepositorySnapshot(REPOSITORY_ID, REPOSITORY_ROOT, REVISION),
                query);
        IllegalStateException failure = new IllegalStateException("extraction failed");
        when(repositorySyntaxProvider.get(any())).thenThrow(failure);
        CapturedLogs capturedLogs = captureLogs();
        try {
            Throwable thrown = catchThrowable(() -> service.search(query));

            assertThat(thrown).isSameAs(failure);
            String phaseMessage = singlePhaseMessage(capturedLogs.events());
            assertThat(phaseMessage)
                    .contains("repositoryRevision=" + REVISION.value())
                    .contains("outcome=FAILED");
            assertThat(timing(phaseMessage, "snapshotAcquireMillis")).isGreaterThanOrEqualTo(0);
            assertThat(timing(phaseMessage, "syntaxExtractionMillis")).isGreaterThanOrEqualTo(0);
            assertThat(timing(phaseMessage, "catalogProjectionMillis")).isEqualTo(-1);
            assertThat(timing(phaseMessage, "matchingMillis")).isEqualTo(-1);
        } finally {
            capturedLogs.stop();
        }
    }

    @Test
    void should_record_elapsed_projection_and_leave_matching_unstarted_when_projection_fails() {
        RepositoryApplicationService repositoryApplicationService = mock(RepositoryApplicationService.class);
        RevisionBoundRepositorySyntaxProvider repositorySyntaxProvider = mock(RevisionBoundRepositorySyntaxProvider.class);
        StructuredConceptCatalogProjector catalogProjector = mock(StructuredConceptCatalogProjector.class);
        when(catalogProjector.supportedKinds()).thenReturn(Set.of(ConceptKind.TYPE));
        ConceptDiscoveryApplicationService service = new ConceptDiscoveryApplicationService(
                repositoryApplicationService,
                repositorySyntaxProvider,
                catalogProjector,
                new ConceptSearchDocumentProjector(),
                new ConceptSearchMatcher());
        ConceptSearchQuery query = query(Set.of(ConceptKind.TYPE));
        delegateSnapshot(
                repositoryApplicationService,
                new RepositorySnapshot(REPOSITORY_ID, REPOSITORY_ROOT, REVISION),
                query);
        RepositorySyntax syntax = new RepositorySyntax(List.of(), List.of(), List.of());
        when(repositorySyntaxProvider.get(any())).thenReturn(syntax);
        IllegalArgumentException failure = new IllegalArgumentException("projection failed");
        when(catalogProjector.project(syntax)).thenThrow(failure);
        CapturedLogs capturedLogs = captureLogs();
        try {
            Throwable thrown = catchThrowable(() -> service.search(query));

            assertThat(thrown).isSameAs(failure);
            String phaseMessage = singlePhaseMessage(capturedLogs.events());
            assertThat(timing(phaseMessage, "snapshotAcquireMillis")).isGreaterThanOrEqualTo(0);
            assertThat(timing(phaseMessage, "syntaxExtractionMillis")).isGreaterThanOrEqualTo(0);
            assertThat(timing(phaseMessage, "catalogProjectionMillis")).isGreaterThanOrEqualTo(0);
            assertThat(timing(phaseMessage, "matchingMillis")).isEqualTo(-1);
        } finally {
            capturedLogs.stop();
        }
    }

    private static ConceptSearchQuery query(Set<ConceptKind> kinds) {
        return new ConceptSearchQuery(
                REPOSITORY_ID,
                REVISION,
                List.of(new ConceptSearchTerm("Order", ConceptMatchMode.TOKEN_PREFIX)),
                kinds,
                Set.of(),
                0,
                10);
    }

    private static StructuredConceptCatalogProjector loggingProjector() {
        return new StructuredConceptCatalogProjector(List.of(new LoggingConceptProvider()));
    }

    /** 提供 logging 測試所需的 active kinds，但不投影候選 */
    private static final class LoggingConceptProvider implements ConceptProvider {

        @Override
        public String providerId() {
            return "logging";
        }

        @Override
        public Set<ConceptKind> supportedKinds() {
            return Set.of(ConceptKind.TYPE, ConceptKind.METHOD, ConceptKind.FIELD, ConceptKind.SCHEDULE);
        }

        @Override
        public ConceptProviderProjection project(RepositorySyntax syntax) {
            return new ConceptProviderProjection(List.of(), List.of());
        }
    }

    private static void delegateSnapshot(
            RepositoryApplicationService repositoryApplicationService,
            RepositorySnapshot snapshot,
            ConceptSearchQuery query) {
        when(repositoryApplicationService.withSnapshot(
                eq(query.repositoryId()), eq(Optional.of(query.expectedRevision())), any()))
                .thenAnswer(invocation -> {
                    Function<RepositorySnapshot, ConceptSearchResult> operation = invocation.getArgument(2);
                    return operation.apply(snapshot);
                });
    }

    private static CapturedLogs captureLogs() {
        Logger logger = (Logger) LoggerFactory.getLogger(ConceptDiscoveryApplicationService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return new CapturedLogs(logger, appender);
    }

    private static List<String> phaseMessages(List<ILoggingEvent> events) {
        return events.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.startsWith("concept_discovery_phase"))
                .toList();
    }

    private static String singlePhaseMessage(List<ILoggingEvent> events) {
        List<String> messages = phaseMessages(events);
        assertThat(messages).hasSize(1);
        return messages.getFirst();
    }

    private static long timing(String message, String fieldName) {
        Pattern fieldPattern = Pattern.compile("(?:^| )" + Pattern.quote(fieldName) + "=(-?\\d+)");
        Matcher matcher = fieldPattern.matcher(message);
        assertThat(matcher.find()).isTrue();
        return Long.parseLong(matcher.group(1));
    }

    private record CapturedLogs(Logger logger, ListAppender<ILoggingEvent> appender) {

        List<ILoggingEvent> events() {
            return List.copyOf(appender.list);
        }

        void stop() {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
