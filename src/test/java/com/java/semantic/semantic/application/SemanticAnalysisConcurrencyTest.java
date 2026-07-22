package com.java.semantic.semantic.application;

import com.java.semantic.callgraph.application.SemanticCallGraphBuilder;
import com.java.semantic.callgraph.domain.OutgoingGraphFragment;
import com.java.semantic.config.OutgoingGraphProperties;
import com.java.semantic.identity.MethodTarget;
import com.java.semantic.repository.application.RepositoryApplicationService;
import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.semantic.domain.JavaSemanticService;
import com.java.semantic.semantic.domain.SemanticDeclarationAnchor;
import com.java.semantic.semantic.domain.SemanticLocation;
import com.java.semantic.semantic.domain.SemanticMethod;
import com.java.semantic.semantic.domain.SemanticPosition;
import com.java.semantic.semantic.domain.SemanticRange;
import com.java.semantic.syntax.domain.RepositorySyntax;
import com.java.semantic.syntax.domain.SyntaxExtractionService;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SemanticAnalysisConcurrencyTest {

    @Test
    void should_keep_concurrent_analysis_callbacks_bound_to_their_own_snapshot() throws Exception {
        RepositoryId firstId = RepositoryId.of("first");
        RepositoryId secondId = RepositoryId.of("second");
        RepositoryRevision firstRevision = RepositoryRevision.ofSha("1".repeat(40));
        RepositoryRevision secondRevision = RepositoryRevision.ofSha("2".repeat(40));
        MethodTarget firstTarget = target("First.java", "First");
        MethodTarget secondTarget = target("Second.java", "Second");
        RepositorySnapshot firstSnapshot = new RepositorySnapshot(firstId, Path.of("/first"), firstRevision);
        RepositorySnapshot secondSnapshot = new RepositorySnapshot(secondId, Path.of("/second"), secondRevision);
        RepositorySyntax firstSyntax = RepositorySyntax.empty();
        RepositorySyntax secondSyntax = RepositorySyntax.empty();
        SemanticMethod firstMethod = method(firstTarget, "/first");
        SemanticMethod secondMethod = method(secondTarget, "/second");
        OutgoingGraphFragment firstResult = mock(OutgoingGraphFragment.class);
        OutgoingGraphFragment secondResult = mock(OutgoingGraphFragment.class);
        RepositoryApplicationService repositories = mock(RepositoryApplicationService.class);
        SyntaxExtractionService syntax = mock(SyntaxExtractionService.class);
        ExactMethodDeclarationResolver resolver = mock(ExactMethodDeclarationResolver.class);
        JavaSemanticService semantic = mock(JavaSemanticService.class);
        SemanticCallGraphBuilder builder = mock(SemanticCallGraphBuilder.class);
        CountDownLatch callbacksReady = new CountDownLatch(2);
        CountDownLatch releaseCallbacks = new CountDownLatch(1);

        when(repositories.withSnapshot(any(), any(), any())).thenAnswer(invocation -> {
            RepositoryId repositoryId = invocation.getArgument(0);
            RepositorySnapshot snapshot = firstId.equals(repositoryId) ? firstSnapshot : secondSnapshot;
            callbacksReady.countDown();
            await(releaseCallbacks);
            Function<RepositorySnapshot, OutgoingGraphFragment> callback = invocation.getArgument(2);
            return callback.apply(snapshot);
        });
        when(syntax.extract(firstSnapshot.root())).thenReturn(firstSyntax);
        when(syntax.extract(secondSnapshot.root())).thenReturn(secondSyntax);
        when(resolver.resolve(firstSyntax, firstTarget)).thenReturn(new SemanticDeclarationAnchor(
                firstTarget, new SemanticPosition(0, 0)));
        when(resolver.resolve(secondSyntax, secondTarget)).thenReturn(new SemanticDeclarationAnchor(
                secondTarget, new SemanticPosition(0, 0)));
        when(semantic.resolveExactMethod(eq(firstSnapshot), any())).thenReturn(firstMethod);
        when(semantic.resolveExactMethod(eq(secondSnapshot), any())).thenReturn(secondMethod);
        when(builder.build(firstSnapshot, firstSyntax, firstTarget, firstMethod, 2, 7)).thenReturn(firstResult);
        when(builder.build(secondSnapshot, secondSyntax, secondTarget, secondMethod, 2, 7)).thenReturn(secondResult);
        SemanticAnalysisApplicationService service = new SemanticAnalysisApplicationService(
                repositories, syntax, resolver, semantic, builder, new OutgoingGraphProperties(7));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<OutgoingGraphFragment> first = executor.submit(
                    () -> service.analyzeOutgoing(firstId, firstRevision, firstTarget, 2));
            Future<OutgoingGraphFragment> second = executor.submit(
                    () -> service.analyzeOutgoing(secondId, secondRevision, secondTarget, 2));
            assertThat(callbacksReady.await(1, TimeUnit.SECONDS)).isTrue();
            releaseCallbacks.countDown();

            assertThat(first.get(1, TimeUnit.SECONDS)).isSameAs(firstResult);
            assertThat(second.get(1, TimeUnit.SECONDS)).isSameAs(secondResult);
        } finally {
            executor.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(1, TimeUnit.SECONDS)) {
                throw new IllegalStateException("analysis callbacks were not released");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("analysis callback was interrupted", exception);
        }
    }

    private static MethodTarget target(String sourceFile, String className) {
        return new MethodTarget(sourceFile, "com.example", className, "run", List.of());
    }

    private static SemanticMethod method(MethodTarget target, String root) {
        SemanticRange range = new SemanticRange(new SemanticPosition(0, 0), new SemanticPosition(1, 0));
        return new SemanticMethod(target.packageName(), target.className(), target.methodName(), target.parameterTypes(),
                "void", new SemanticLocation("file://" + root + "/" + target.sourceFile(), range, range));
    }
}
