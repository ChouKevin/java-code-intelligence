package com.java.semantic.syntax.adapter.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.Ticker;
import com.java.semantic.config.RepositorySyntaxCacheProperties;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.syntax.domain.RepositorySyntax;
import com.java.semantic.syntax.domain.RevisionBoundRepositorySyntaxProvider;
import com.java.semantic.syntax.domain.SyntaxExtractionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** 以 Caffeine 快取精確 repository revision 的 RepositorySyntax adapter */
public final class CaffeineRevisionBoundRepositorySyntaxProvider
        implements RevisionBoundRepositorySyntaxProvider {

    private static final Logger LOG = LoggerFactory.getLogger(CaffeineRevisionBoundRepositorySyntaxProvider.class);

    /** 接收 RepositorySyntax 快取的安全監控結果 */
    @FunctionalInterface
    interface CacheMonitor {

        void record(
                RepositorySyntaxCacheKey key,
                String outcome,
                boolean cacheStored,
                int structuralWeight,
                Duration loadDuration);
    }

    private final SyntaxExtractionService syntaxExtractionService;
    private final RepositorySyntaxWeightEstimator weightEstimator;
    private final Cache<RepositorySyntaxCacheKey, RepositorySyntax> cache;
    private final int maximumEntryWeight;
    private final CacheMonitor cacheMonitor;

    public CaffeineRevisionBoundRepositorySyntaxProvider(
            SyntaxExtractionService syntaxExtractionService,
            RepositorySyntaxCacheProperties properties) {
        this(syntaxExtractionService, properties, Ticker.systemTicker());
    }

    CaffeineRevisionBoundRepositorySyntaxProvider(
            SyntaxExtractionService syntaxExtractionService,
            RepositorySyntaxCacheProperties properties,
            Ticker ticker) {
        this(syntaxExtractionService, properties, ticker,
                CaffeineRevisionBoundRepositorySyntaxProvider::logObservation);
    }

    CaffeineRevisionBoundRepositorySyntaxProvider(
            SyntaxExtractionService syntaxExtractionService,
            RepositorySyntaxCacheProperties properties,
            Ticker ticker,
            CacheMonitor cacheMonitor) {
        this.syntaxExtractionService = Objects.requireNonNull(
                syntaxExtractionService, "syntaxExtractionService is required");
        RepositorySyntaxCacheProperties requiredProperties = Objects.requireNonNull(
                properties, "properties is required");
        Ticker requiredTicker = Objects.requireNonNull(ticker, "ticker is required");
        this.cacheMonitor = Objects.requireNonNull(cacheMonitor, "cacheMonitor is required");
        this.maximumEntryWeight = requiredProperties.maximumEntryWeight();
        this.weightEstimator = new RepositorySyntaxWeightEstimator();
        this.cache = Caffeine.newBuilder()
                .maximumWeight(requiredProperties.maximumWeight())
                .expireAfterAccess(requiredProperties.expireAfterAccess())
                .ticker(requiredTicker)
                .weigher((RepositorySyntaxCacheKey ignored, RepositorySyntax syntax) -> cacheWeight(syntax,
                        requiredProperties.maximumWeight()))
                .removalListener(this::logEviction)
                .build();
    }

    @Override
    public RepositorySyntax get(RepositorySnapshot snapshot) {
        RepositorySnapshot requiredSnapshot = Objects.requireNonNull(snapshot, "snapshot is required");
        RepositorySyntaxCacheKey key = new RepositorySyntaxCacheKey(
                requiredSnapshot.repositoryId(), requiredSnapshot.revision());
        long lookupStartedAt = System.nanoTime();
        cache.cleanUp();
        RepositorySyntax cachedSyntax = cache.getIfPresent(key);
        if (Objects.nonNull(cachedSyntax)) {
            log(key, "HIT", true, weightEstimator.estimate(cachedSyntax), Duration.ZERO);
            return cachedSyntax;
        }

        AtomicReference<RepositorySyntax> loadedSyntax = new AtomicReference<>();
        AtomicLong loadDurationNanos = new AtomicLong();
        try {
            RepositorySyntax syntax = cache.get(key, ignored -> load(requiredSnapshot, loadedSyntax, loadDurationNanos));
            RepositorySyntax requiredSyntax = Objects.requireNonNull(syntax, "cache result is required");
            int structuralWeight = weightEstimator.estimate(requiredSyntax);
            cache.cleanUp();
            boolean stored = Objects.nonNull(cache.getIfPresent(key));
            boolean loadedByThisCall = Objects.nonNull(loadedSyntax.get());
            Duration duration = loadedByThisCall
                    ? Duration.ofNanos(loadDurationNanos.get())
                    : Duration.ofNanos(System.nanoTime() - lookupStartedAt);
            String outcome = loadedByThisCall ? "LOADED" : "HIT";
            if (structuralWeight > maximumEntryWeight) {
                outcome = "NOT_CACHED_OVERSIZED";
            }
            log(key, outcome, stored, structuralWeight, duration);
            return requiredSyntax;
        } catch (RuntimeException exception) {
            log(key, "LOAD_FAILED", false, 0, Duration.ofNanos(System.nanoTime() - lookupStartedAt));
            throw exception;
        }
    }

    private RepositorySyntax load(
            RepositorySnapshot snapshot,
            AtomicReference<RepositorySyntax> loadedSyntax,
            AtomicLong loadDurationNanos) {
        long loadStartedAt = System.nanoTime();
        try {
            RepositorySyntax syntax = Objects.requireNonNull(
                    syntaxExtractionService.extract(snapshot.root()), "syntax extraction result is required");
            loadedSyntax.set(syntax);
            return syntax;
        } finally {
            loadDurationNanos.set(System.nanoTime() - loadStartedAt);
        }
    }

    private int cacheWeight(RepositorySyntax syntax, int maximumWeight) {
        int structuralWeight = weightEstimator.estimate(syntax);
        if (structuralWeight > maximumEntryWeight) {
            return maximumWeight + 1;
        }
        return structuralWeight;
    }

    private void logEviction(
            RepositorySyntaxCacheKey key,
            RepositorySyntax syntax,
            RemovalCause cause) {
        if (Objects.nonNull(key) && Objects.nonNull(syntax) && cause.wasEvicted()) {
            log(key, "EVICTED", false, weightEstimator.estimate(syntax), Duration.ZERO);
        }
    }

    private void log(
            RepositorySyntaxCacheKey key,
            String outcome,
            boolean cacheStored,
            int structuralWeight,
            Duration loadDuration) {
        cacheMonitor.record(key, outcome, cacheStored, structuralWeight, loadDuration);
    }

    private static void logObservation(
            RepositorySyntaxCacheKey key,
            String outcome,
            boolean cacheStored,
            int structuralWeight,
            Duration loadDuration) {
        LOG.info(
                "repository_syntax_cache repoId={} revision={} outcome={} cacheStored={} structuralWeight={} loadDurationMs={}",
                key.repositoryId(),
                key.revision(),
                outcome,
                cacheStored,
                structuralWeight,
                loadDuration.toMillis());
    }
}
