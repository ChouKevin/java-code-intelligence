package com.java.semantic.semantic.adapter.cache;

import com.java.semantic.config.InternalReferenceCacheProperties;
import com.java.semantic.identity.JavaTypeIdentity;
import com.java.semantic.identity.SourceTypeIdentity;
import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;
import com.java.semantic.semantic.application.InternalReferenceAnalysis;
import com.java.semantic.semantic.application.InternalReferenceAnalysisCache;
import com.java.semantic.semantic.application.InternalReferenceStatus;
import com.java.semantic.syntax.domain.ExactSourceDeclaration;
import com.java.semantic.syntax.domain.ExactSourceDeclarationTarget;
import com.java.semantic.syntax.domain.SyntaxPosition;
import com.java.semantic.syntax.domain.SyntaxRange;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CaffeineInternalReferenceAnalysisCacheTest {

    @Test
    void should_single_flight_store_only_eligible_complete_analyses_and_apply_configured_bounds() throws Exception {
        InternalReferenceCacheProperties properties = new InternalReferenceCacheProperties(
                5, Duration.ofMillis(40), 3);
        AtomicLong tickerNanos = new AtomicLong();
        CaffeineInternalReferenceAnalysisCache cache = new CaffeineInternalReferenceAnalysisCache(
                properties, tickerNanos::get, Runnable::run);
        InternalReferenceAnalysisCache.Key key = key("a");
        InternalReferenceAnalysis complete = analysis(InternalReferenceStatus.COMPLETE, 1);
        AtomicInteger loads = new AtomicInteger();
        CountDownLatch loaderStarted = new CountDownLatch(1);
        CountDownLatch releaseLoader = new CountDownLatch(1);
        CountDownLatch waiterStarted = new CountDownLatch(1);
        AtomicReference<Thread> waiterThread = new AtomicReference<>();
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<InternalReferenceAnalysisCache.LookupResult> first = executor.submit(() -> cache.lookup(key, () -> {
                loads.incrementAndGet();
                loaderStarted.countDown();
                awaitRelease(releaseLoader);
                return complete;
            }));
            assertThat(loaderStarted.await(1, TimeUnit.SECONDS)).isTrue();
            Future<InternalReferenceAnalysisCache.LookupResult> second = executor.submit(() -> {
                waiterThread.set(Thread.currentThread());
                waiterStarted.countDown();
                return cache.lookup(key, () -> {
                    loads.incrementAndGet();
                    return complete;
                });
            });
            assertThat(waiterStarted.await(1, TimeUnit.SECONDS)).isTrue();
            awaitWaiting(waiterThread.get());
            releaseLoader.countDown();

            InternalReferenceAnalysisCache.LookupResult owner = first.get(1, TimeUnit.SECONDS);
            InternalReferenceAnalysisCache.LookupResult waiter = second.get(1, TimeUnit.SECONDS);
            assertThat(owner.value()).isEqualTo(complete);
            assertThat(owner.cacheHit()).isFalse();
            assertThat(owner.cacheStored()).isTrue();
            assertThat(owner.loadDuration()).isPositive();
            assertThat(waiter.value()).isEqualTo(complete);
            assertThat(waiter.cacheHit()).isFalse();
            assertThat(waiter.cacheStored()).isTrue();
            assertThat(waiter.loadDuration()).isPositive();
            assertThat(loads).hasValue(1);
            InternalReferenceAnalysisCache.LookupResult hit = cache.lookup(key, () -> {
                loads.incrementAndGet();
                return complete;
            });
            assertThat(hit.cacheHit()).isTrue();
            assertThat(hit.cacheStored()).isTrue();
            assertThat(hit.loadDuration()).isZero();

            tickerNanos.addAndGet(Duration.ofMillis(60).toNanos());
            cache.lookup(key, () -> {
                loads.incrementAndGet();
                return complete;
            });
            assertThat(loads).hasValue(2);

            InternalReferenceAnalysis partial = analysis(InternalReferenceStatus.PARTIAL, 1);
            AtomicInteger partialLoads = new AtomicInteger();
            cache.lookup(key("b"), () -> {
                partialLoads.incrementAndGet();
                return partial;
            });
            InternalReferenceAnalysisCache.LookupResult partialAgain = cache.lookup(key("b"), () -> {
                partialLoads.incrementAndGet();
                return partial;
            });
            assertThat(partialLoads).hasValue(2);
            assertThat(partialAgain.cacheStored()).isFalse();

            InternalReferenceAnalysis overweight = analysis(InternalReferenceStatus.COMPLETE, 4);
            AtomicInteger overweightLoads = new AtomicInteger();
            InternalReferenceAnalysisCache.LookupResult overweightResult = cache.lookup(key("c"), () -> {
                overweightLoads.incrementAndGet();
                return overweight;
            });
            cache.lookup(key("c"), () -> {
                overweightLoads.incrementAndGet();
                return overweight;
            });
            assertThat(overweightResult.value()).isEqualTo(overweight);
            assertThat(overweightResult.cacheStored()).isFalse();
            assertThat(overweightLoads).hasValue(2);

            AtomicInteger failureLoads = new AtomicInteger();
            assertThatThrownBy(() -> cache.lookup(key("d"), () -> {
                failureLoads.incrementAndGet();
                throw new IllegalStateException("planned failure");
            })).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> cache.lookup(key("d"), () -> {
                failureLoads.incrementAndGet();
                throw new IllegalStateException("planned failure");
            })).isInstanceOf(IllegalStateException.class);
            assertThat(failureLoads).hasValue(2);

            InternalReferenceAnalysis weighted = analysis(InternalReferenceStatus.COMPLETE, 3);
            cache.lookup(key("e"), () -> weighted);
            cache.lookup(key("f"), () -> weighted);
            AtomicInteger evictedReloads = new AtomicInteger();
            cache.lookup(key("e"), () -> {
                evictedReloads.incrementAndGet();
                return weighted;
            });
            cache.lookup(key("f"), () -> {
                evictedReloads.incrementAndGet();
                return weighted;
            });
            assertThat(evictedReloads.get()).isGreaterThanOrEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void should_never_publish_an_ineligible_same_key_mapping_to_a_concurrent_caller() throws Exception {
        InternalReferenceCacheProperties properties = new InternalReferenceCacheProperties(
                5, Duration.ofMinutes(1), 3);
        CaffeineInternalReferenceAnalysisCache cache = new CaffeineInternalReferenceAnalysisCache(properties);
        InternalReferenceAnalysisCache.Key key = key("b");
        InternalReferenceAnalysis firstPartial = analysis(InternalReferenceStatus.PARTIAL, 1);
        InternalReferenceAnalysis secondPartial = analysis(InternalReferenceStatus.PARTIAL, 2);
        AtomicInteger loads = new AtomicInteger();
        CountDownLatch firstLoaderStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstLoader = new CountDownLatch(1);
        CountDownLatch secondLookupStarted = new CountDownLatch(1);
        AtomicReference<Thread> secondThread = new AtomicReference<>();
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<InternalReferenceAnalysisCache.LookupResult> first = executor.submit(() -> cache.lookup(key, () -> {
                loads.incrementAndGet();
                firstLoaderStarted.countDown();
                awaitRelease(releaseFirstLoader);
                return firstPartial;
            }));
            assertThat(firstLoaderStarted.await(1, TimeUnit.SECONDS)).isTrue();
            Future<InternalReferenceAnalysisCache.LookupResult> second = executor.submit(() -> {
                secondThread.set(Thread.currentThread());
                secondLookupStarted.countDown();
                return cache.lookup(key, () -> {
                    loads.incrementAndGet();
                    return secondPartial;
                });
            });
            assertThat(secondLookupStarted.await(1, TimeUnit.SECONDS)).isTrue();
            awaitWaiting(secondThread.get());
            releaseFirstLoader.countDown();

            InternalReferenceAnalysisCache.LookupResult firstResult = first.get(1, TimeUnit.SECONDS);
            InternalReferenceAnalysisCache.LookupResult secondResult = second.get(1, TimeUnit.SECONDS);
            assertThat(firstResult.value()).isEqualTo(firstPartial);
            assertThat(firstResult.cacheHit()).isFalse();
            assertThat(firstResult.cacheStored()).isFalse();
            assertThat(secondResult.value()).isEqualTo(secondPartial);
            assertThat(secondResult.cacheHit()).isFalse();
            assertThat(secondResult.cacheStored()).isFalse();
            assertThat(loads).hasValue(2);
        } finally {
            executor.shutdownNow();
        }
    }

    private InternalReferenceAnalysisCache.Key key(String suffix) {
        SourceTypeIdentity sourceType = new SourceTypeIdentity(
                new JavaTypeIdentity("com.example", "Type" + suffix.toUpperCase()),
                "src/main/java/com/example/Type" + suffix.toUpperCase() + ".java");
        return new InternalReferenceAnalysisCache.Key(
                RepositoryId.of("repo-" + suffix),
                RepositoryRevision.ofSha(suffix.repeat(40)),
                new ExactSourceDeclarationTarget.Type(sourceType));
    }

    private void awaitRelease(CountDownLatch releaseLoader) {
        try {
            if (!releaseLoader.await(1, TimeUnit.SECONDS)) {
                throw new IllegalStateException("loader was not released");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("loader was interrupted", exception);
        }
    }

    private void awaitWaiting(Thread thread) {
        Thread requiredThread = Objects.requireNonNull(thread, "thread is required");
        long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
        while (System.nanoTime() < deadline) {
            Thread.State state = requiredThread.getState();
            if (Thread.State.BLOCKED.equals(state)
                    || Thread.State.WAITING.equals(state)
                    || Thread.State.TIMED_WAITING.equals(state)) {
                return;
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("thread did not wait for the in-flight cache mapping");
    }

    private InternalReferenceAnalysis analysis(InternalReferenceStatus status, int entryWeight) {
        ExactSourceDeclarationTarget target = key("a").target();
        SyntaxRange range = new SyntaxRange(new SyntaxPosition(0, 0), new SyntaxPosition(0, 1));
        return new InternalReferenceAnalysis(
                new ExactSourceDeclaration(target, range, range),
                status,
                0,
                List.of(),
                List.of(),
                0,
                0,
                0,
                entryWeight);
    }
}
