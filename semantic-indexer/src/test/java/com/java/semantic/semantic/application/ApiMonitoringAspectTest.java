package com.java.semantic.semantic.application;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.java.semantic.model.codefact.JavaTypeIdentity;
import com.java.semantic.model.codefact.SourceTypeIdentity;
import com.java.semantic.repository.application.RepositoryApplicationService;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.semantic.domain.JavaSemanticService;
import com.java.semantic.syntax.domain.ExactSourceDeclaration;
import com.java.semantic.syntax.domain.ExactSourceDeclarationResolver;
import com.java.semantic.syntax.domain.ExactSourceDeclarationTarget;
import com.java.semantic.syntax.domain.RevisionBoundRepositorySyntaxProvider;
import com.java.semantic.model.codefact.SyntaxPosition;
import com.java.semantic.model.codefact.SyntaxRange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 內部 reference 固定形狀效能事件測試 */
class ApiMonitoringAspectTest {

    private static final RepositoryId REPOSITORY_ID = RepositoryId.of("orders");
    private static final RepositoryRevision REVISION = RepositoryRevision.ofSha("a".repeat(40));

    @TempDir
    Path repositoryRoot;

    @Test
    void should_emit_privacy_safe_fixed_shape_internal_reference_performance_fields() {
        SourceTypeIdentity sourceType = new SourceTypeIdentity(
                new JavaTypeIdentity("com.example.secret", "SensitiveTarget"),
                "src/main/java/com/example/secret/SensitiveTarget.java");
        ExactSourceDeclarationTarget target = new ExactSourceDeclarationTarget.Type(sourceType);
        SyntaxRange range = new SyntaxRange(new SyntaxPosition(1, 0), new SyntaxPosition(1, 15));
        ExactSourceDeclaration declaration = new ExactSourceDeclaration(target, range, range);
        InternalReferenceAnalysis analysis = new InternalReferenceAnalysis(
                declaration,
                InternalReferenceStatus.COMPLETE,
                0,
                List.of(),
                List.of(),
                4,
                2,
                1,
                1);
        InternalReferenceAnalysisCache cache = (key, loader) ->
                new InternalReferenceAnalysisCache.LookupResult(
                        analysis, true, true, 1, Duration.ofMillis(7));
        RepositoryApplicationService repositories = repositoryService();
        InternalSourceReferenceApplicationService service = new InternalSourceReferenceApplicationService(
                repositories,
                mock(ExactSourceDeclarationResolver.class),
                mock(JavaSemanticService.class),
                mock(RevisionBoundRepositorySyntaxProvider.class),
                cache);
        CapturedLogs captured = captureLogs();
        try {
            service.find(new InternalSourceReferenceQuery(REPOSITORY_ID, REVISION, target, 0, 20));

            List<String> messages = captured.messages();
            assertThat(messages)
                    .singleElement()
                    .matches(message -> message.matches(
                            "internal_reference_performance repoId=orders expectedRevision="
                                    + REVISION.value()
                                    + " targetKind=TYPE cacheHit=true cacheStored=true cacheEntryWeight=1 "
                                    + "cacheLoadDurationMs=7 rawLocationCount=4 repositoryLocalReferenceCount=2 "
                                    + "hitFileCount=1 totalGroupCount=0 status=COMPLETE durationMs=\\d+"));
            assertThat(messages).allSatisfy(message -> assertThat(message)
                    .doesNotContain("SensitiveTarget", "com.example.secret", "src/main"));
        } finally {
            captured.stop();
        }
    }

    private RepositoryApplicationService repositoryService() {
        RepositoryApplicationService repositories = mock(RepositoryApplicationService.class);
        RepositorySnapshot snapshot = new RepositorySnapshot(REPOSITORY_ID, repositoryRoot, REVISION);
        when(repositories.withSnapshot(any(), any(), any())).thenAnswer(invocation -> {
            Function<RepositorySnapshot, Object> operation = invocation.getArgument(2);
            return operation.apply(snapshot);
        });
        return repositories;
    }

    private CapturedLogs captureLogs() {
        Logger logger = (Logger) LoggerFactory.getLogger(InternalSourceReferenceApplicationService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return new CapturedLogs(logger, appender);
    }

    private record CapturedLogs(Logger logger, ListAppender<ILoggingEvent> appender) {

        private List<String> messages() {
            return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        }

        private void stop() {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
