package com.java.semantic.syntax.adapter.cache;

import com.github.benmanes.caffeine.cache.Ticker;
import com.java.semantic.config.RepositorySyntaxCacheProperties;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.model.codefact.JavaTypeIdentity;
import com.java.semantic.model.codefact.MethodTarget;
import com.java.semantic.model.codefact.SourceTypeIdentity;
import com.java.semantic.syntax.domain.RepositorySyntax;
import com.java.semantic.syntax.domain.SourceExtractionOutcome;
import com.java.semantic.syntax.domain.SourceFieldMetadata;
import com.java.semantic.model.codefact.SourceRange;
import com.java.semantic.syntax.domain.SourceTypeMetadataFixture;
import com.java.semantic.syntax.domain.SourceTypeKind;
import com.java.semantic.syntax.domain.NamedTypeReference;
import com.java.semantic.syntax.domain.ParameterizedTypeReference;
import com.java.semantic.model.codefact.SyntaxPosition;
import com.java.semantic.model.codefact.SyntaxRange;
import com.java.semantic.syntax.domain.SyntaxExtractionService;
import com.java.semantic.syntax.domain.TypeReference;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 驗證 revision-scoped RepositorySyntax Caffeine 快取的公開行為 */
class CaffeineRevisionBoundRepositorySyntaxProviderTest {

    @Test
    void should_isolate_different_revisions_of_the_same_repository() {
        SyntaxExtractionService extractionService = mock(SyntaxExtractionService.class);
        RepositorySyntax firstSyntax = RepositorySyntax.empty();
        RepositorySyntax secondSyntax = new RepositorySyntax(
                List.of(), List.of(), List.of(SourceExtractionOutcome.extracted("Second.java")));
        RepositorySnapshot firstSnapshot = snapshot("orders", "1", "/repository/orders");
        RepositorySnapshot secondSnapshot = snapshot("orders", "2", "/repository/orders");
        when(extractionService.extract(firstSnapshot.root())).thenReturn(firstSyntax, secondSyntax);
        CaffeineRevisionBoundRepositorySyntaxProvider provider = provider(extractionService);

        RepositorySyntax first = provider.get(firstSnapshot);
        RepositorySyntax second = provider.get(secondSnapshot);

        assertThat(first).isSameAs(firstSyntax);
        assertThat(second).isSameAs(secondSyntax);
        verify(extractionService, times(2)).extract(firstSnapshot.root());
    }

    @Test
    void should_reuse_the_same_revision_result() {
        SyntaxExtractionService extractionService = mock(SyntaxExtractionService.class);
        RepositorySyntax syntax = RepositorySyntax.empty();
        RepositorySnapshot snapshot = snapshot("orders", "1", "/repository/orders");
        when(extractionService.extract(snapshot.root())).thenReturn(syntax);
        CaffeineRevisionBoundRepositorySyntaxProvider provider = provider(extractionService);

        RepositorySyntax first = provider.get(snapshot);
        RepositorySyntax second = provider.get(snapshot);

        assertThat(second).isSameAs(first);
        verify(extractionService).extract(snapshot.root());
    }

    @Test
    void should_mark_only_the_concurrent_mapping_owner_as_loaded() throws Exception {
        SyntaxExtractionService extractionService = mock(SyntaxExtractionService.class);
        RepositorySnapshot snapshot = snapshot("orders", "1", "/repository/orders");
        RepositorySyntax syntax = RepositorySyntax.empty();
        AtomicInteger extractions = new AtomicInteger();
        CountDownLatch extractionStarted = new CountDownLatch(1);
        CountDownLatch releaseExtraction = new CountDownLatch(1);
        CountDownLatch waiterStarted = new CountDownLatch(1);
        Queue<String> outcomes = new ConcurrentLinkedQueue<>();
        doAnswer(invocation -> {
            extractions.incrementAndGet();
            extractionStarted.countDown();
            await(releaseExtraction);
            return syntax;
        }).when(extractionService).extract(snapshot.root());
        CaffeineRevisionBoundRepositorySyntaxProvider.CacheMonitor monitor =
                (key, outcome, cacheStored, structuralWeight, loadDuration) -> outcomes.add(outcome);
        CaffeineRevisionBoundRepositorySyntaxProvider provider = new CaffeineRevisionBoundRepositorySyntaxProvider(
                extractionService,
                new RepositorySyntaxCacheProperties(100, 100, Duration.ofMinutes(1)),
                Ticker.systemTicker(),
                monitor);
        FutureTask<RepositorySyntax> loaderTask = new FutureTask<>(() -> provider.get(snapshot));
        Thread loaderThread = new Thread(loaderTask, "syntax-cache-loader-test");
        FutureTask<RepositorySyntax> waiterTask = new FutureTask<>(() -> {
            waiterStarted.countDown();
            return provider.get(snapshot);
        });
        Thread waiterThread = new Thread(waiterTask, "syntax-cache-waiter-test");

        try {
            loaderThread.start();
            assertThat(extractionStarted.await(1, TimeUnit.SECONDS)).isTrue();
            waiterThread.start();
            assertThat(waiterStarted.await(1, TimeUnit.SECONDS)).isTrue();
            awaitWaiting(waiterThread);
        } finally {
            releaseExtraction.countDown();
        }

        assertThat(loaderTask.get(1, TimeUnit.SECONDS)).isSameAs(syntax);
        assertThat(waiterTask.get(1, TimeUnit.SECONDS)).isSameAs(syntax);
        assertThat(extractions).hasValue(1);
        assertThat(outcomes).containsExactlyInAnyOrder("LOADED", "HIT");
    }

    @Test
    void should_not_cache_a_failed_load() {
        SyntaxExtractionService extractionService = mock(SyntaxExtractionService.class);
        RepositorySnapshot snapshot = snapshot("orders", "1", "/repository/orders");
        RepositorySyntax syntax = RepositorySyntax.empty();
        when(extractionService.extract(snapshot.root()))
                .thenThrow(new IllegalStateException("synthetic extraction failure"))
                .thenReturn(syntax);
        CaffeineRevisionBoundRepositorySyntaxProvider provider = provider(extractionService);

        assertThatThrownBy(() -> provider.get(snapshot))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("synthetic extraction failure");

        assertThat(provider.get(snapshot)).isSameAs(syntax);
    }

    @Test
    void should_return_uncached_extraction_when_cache_weight_estimation_fails() {
        SyntaxExtractionService extractionService = mock(SyntaxExtractionService.class);
        RepositorySyntaxWeightEstimator weightEstimator = mock(RepositorySyntaxWeightEstimator.class);
        RepositorySnapshot snapshot = snapshot("orders", "1", "/repository/orders");
        RepositorySyntax syntax = RepositorySyntax.empty();
        Queue<String> outcomes = new ConcurrentLinkedQueue<>();
        when(extractionService.extract(snapshot.root())).thenReturn(syntax);
        when(weightEstimator.estimate(syntax))
                .thenThrow(new IllegalStateException("synthetic cache bookkeeping failure"));
        CaffeineRevisionBoundRepositorySyntaxProvider.CacheMonitor monitor =
                (key, outcome, cacheStored, structuralWeight, loadDuration) -> outcomes.add(outcome);
        CaffeineRevisionBoundRepositorySyntaxProvider provider = new CaffeineRevisionBoundRepositorySyntaxProvider(
                extractionService,
                new RepositorySyntaxCacheProperties(100, 100, Duration.ofMinutes(1)),
                Ticker.systemTicker(),
                monitor,
                weightEstimator);

        assertThat(provider.get(snapshot)).isSameAs(syntax);
        assertThat(provider.get(snapshot)).isSameAs(syntax);

        verify(extractionService, times(2)).extract(snapshot.root());
        assertThat(outcomes).containsExactly("BYPASSED_CACHE_FAILURE", "BYPASSED_CACHE_FAILURE");
    }

    @Test
    void should_serve_an_oversized_result_without_admitting_it() {
        SyntaxExtractionService extractionService = mock(SyntaxExtractionService.class);
        RepositorySnapshot snapshot = snapshot("orders", "1", "/repository/orders");
        RepositorySyntax oversizedSyntax = new RepositorySyntax(
                List.of(), List.of(), List.of(SourceExtractionOutcome.extracted("Oversized.java")));
        when(extractionService.extract(snapshot.root())).thenReturn(oversizedSyntax);
        CaffeineRevisionBoundRepositorySyntaxProvider provider = new CaffeineRevisionBoundRepositorySyntaxProvider(
                extractionService,
                new RepositorySyntaxCacheProperties(100, 1, Duration.ofMinutes(1)),
                Ticker.systemTicker());

        RepositorySyntax first = provider.get(snapshot);
        RepositorySyntax second = provider.get(snapshot);

        assertThat(first).isSameAs(oversizedSyntax);
        assertThat(second).isSameAs(oversizedSyntax);
        verify(extractionService, times(2)).extract(snapshot.root());
    }

    @Test
    void should_not_admit_a_single_type_when_nested_members_exceed_the_maximum_entry_weight() {
        SyntaxExtractionService extractionService = mock(SyntaxExtractionService.class);
        RepositorySnapshot snapshot = snapshot("orders", "1", "/repository/orders");
        MethodTarget target = new MethodTarget(
                new SourceTypeIdentity(new JavaTypeIdentity("com.acme", "NestedType"), "NestedType.java"),
                "method0",
                List.of());
        NamedTypeReference rawType = new NamedTypeReference(
                "java.util.List", "List", Optional.empty(), false);
        List<TypeReference> typeArguments = IntStream.range(0, 128)
                .mapToObj(index -> new NamedTypeReference(
                        "com.acme.Nested" + index, "Nested" + index, Optional.empty(), true))
                .map(TypeReference.class::cast)
                .toList();
        SourceFieldMetadata field = new SourceFieldMetadata(
                "nestedMembers",
                "java.util.List",
                "",
                new ParameterizedTypeReference("java.util.List", rawType, typeArguments),
                List.of(), com.java.semantic.model.codefact.CodeFactKind.FIELD, new com.java.semantic.model.codefact.SourceRange("CaffeineRevisionBoundRepositorySyntaxProviderTest.java", new com.java.semantic.model.codefact.SyntaxRange(new com.java.semantic.model.codefact.SyntaxPosition(0, 0), new com.java.semantic.model.codefact.SyntaxPosition(0, 1))));
        SyntaxRange declarationRange = new SyntaxRange(
                new SyntaxPosition(0, 0), new SyntaxPosition(1, 0));
        RepositorySyntax nestedSyntax = new RepositorySyntax(
                List.of(),
                List.of(SourceTypeMetadataFixture.sourceType(
                        target.className(),
                        target.packageName(),
                        target.fullyQualifiedClassName(),
                        target.sourceFile(),
                        SourceTypeKind.CLASS,
                        false,
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(field),
                        List.of(),
                        false,
                        false,
                        List.of(),
                        declarationRange,
                        new SourceRange(target.sourceFile(), declarationRange),
                        false,
                        List.of())),
                List.of());
        when(extractionService.extract(snapshot.root())).thenReturn(nestedSyntax);
        CaffeineRevisionBoundRepositorySyntaxProvider provider = new CaffeineRevisionBoundRepositorySyntaxProvider(
                extractionService,
                new RepositorySyntaxCacheProperties(100, 100, Duration.ofMinutes(1)),
                Ticker.systemTicker());

        assertThat(provider.get(snapshot)).isSameAs(nestedSyntax);
        assertThat(provider.get(snapshot)).isSameAs(nestedSyntax);

        verify(extractionService, times(2)).extract(snapshot.root());
    }

    @Test
    void should_reload_a_normal_entry_after_access_expiry() {
        SyntaxExtractionService extractionService = mock(SyntaxExtractionService.class);
        RepositorySnapshot snapshot = snapshot("orders", "1", "/repository/orders");
        RepositorySyntax syntax = RepositorySyntax.empty();
        AtomicLong tickerNanos = new AtomicLong();
        Ticker ticker = tickerNanos::get;
        when(extractionService.extract(snapshot.root())).thenReturn(syntax);
        CaffeineRevisionBoundRepositorySyntaxProvider provider = new CaffeineRevisionBoundRepositorySyntaxProvider(
                extractionService,
                new RepositorySyntaxCacheProperties(100, 100, Duration.ofSeconds(1)),
                ticker);

        assertThat(provider.get(snapshot)).isSameAs(syntax);
        tickerNanos.addAndGet(Duration.ofSeconds(1).plusNanos(1).toNanos());

        assertThat(provider.get(snapshot)).isSameAs(syntax);
        verify(extractionService, times(2)).extract(snapshot.root());
    }

    private CaffeineRevisionBoundRepositorySyntaxProvider provider(SyntaxExtractionService extractionService) {
        return new CaffeineRevisionBoundRepositorySyntaxProvider(
                extractionService,
                new RepositorySyntaxCacheProperties(100, 100, Duration.ofMinutes(1)),
                Ticker.systemTicker());
    }

    private RepositorySnapshot snapshot(String repositoryId, String revisionSuffix, String root) {
        return new RepositorySnapshot(
                RepositoryId.of(repositoryId),
                Path.of(root),
                RepositoryRevision.ofSha("000000000000000000000000000000000000000" + revisionSuffix));
    }

    private void await(CountDownLatch latch) throws InterruptedException {
        if (!latch.await(1, TimeUnit.SECONDS)) {
            throw new IllegalStateException("test extraction was not released");
        }
    }

    private void awaitWaiting(Thread thread) {
        long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
        while (System.nanoTime() < deadline) {
            Thread.State state = thread.getState();
            if (state == Thread.State.WAITING || state == Thread.State.BLOCKED || state == Thread.State.TIMED_WAITING) {
                return;
            }
            Thread.onSpinWait();
        }
        throw new IllegalStateException("concurrent cache caller did not wait for the active load");
    }
}
