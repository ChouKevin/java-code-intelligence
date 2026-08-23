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

    /** 接收 RepositorySyntax 快取的安全監控結果，LOADED 記 extraction 時間，其餘結果記 lookup wall time或零 */
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
    private final Cache<RepositorySyntaxCacheKey, CachedRepositorySyntax> cache;
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
        this(syntaxExtractionService, properties, ticker, cacheMonitor, new RepositorySyntaxWeightEstimator());
    }

    CaffeineRevisionBoundRepositorySyntaxProvider(
            SyntaxExtractionService syntaxExtractionService,
            RepositorySyntaxCacheProperties properties,
            Ticker ticker,
            CacheMonitor cacheMonitor,
            RepositorySyntaxWeightEstimator weightEstimator) {
        this.syntaxExtractionService = Objects.requireNonNull(
                syntaxExtractionService, "syntaxExtractionService is required");
        RepositorySyntaxCacheProperties requiredProperties = Objects.requireNonNull(
                properties, "properties is required");
        Ticker requiredTicker = Objects.requireNonNull(ticker, "ticker is required");
        this.cacheMonitor = Objects.requireNonNull(cacheMonitor, "cacheMonitor is required");
        this.maximumEntryWeight = requiredProperties.maximumEntryWeight();
        this.weightEstimator = Objects.requireNonNull(weightEstimator, "weightEstimator is required");
        this.cache = Caffeine.newBuilder()
                .maximumWeight(requiredProperties.maximumWeight())
                .expireAfterAccess(requiredProperties.expireAfterAccess())
                .ticker(requiredTicker)
                .weigher((RepositorySyntaxCacheKey ignored, CachedRepositorySyntax cachedSyntax) -> cacheWeight(
                        cachedSyntax.structuralWeight(),
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
        CachedRepositorySyntax cachedSyntax = cache.getIfPresent(key);
        if (Objects.nonNull(cachedSyntax)) {
            log(key, "HIT", true, cachedSyntax.structuralWeight(), Duration.ZERO);
            return cachedSyntax.syntax();
        }

        AtomicReference<CachedRepositorySyntax> loadedSyntax = new AtomicReference<>();
        AtomicLong loadDurationNanos = new AtomicLong();
        try {
            CachedRepositorySyntax syntax = cache.get(
                    key, ignored -> load(requiredSnapshot, loadedSyntax, loadDurationNanos));
            CachedRepositorySyntax requiredSyntax = Objects.requireNonNull(syntax, "cache result is required");
            cache.cleanUp();
            boolean stored = Objects.nonNull(cache.getIfPresent(key));
            boolean loadedByThisCall = Objects.nonNull(loadedSyntax.get());
            Duration duration = loadedByThisCall
                    ? Duration.ofNanos(loadDurationNanos.get())
                    : Duration.ofNanos(System.nanoTime() - lookupStartedAt);
            String outcome = loadedByThisCall ? "LOADED" : "HIT";
            if (requiredSyntax.structuralWeight() > maximumEntryWeight) {
                outcome = "NOT_CACHED_OVERSIZED";
            }
            log(key, outcome, stored, requiredSyntax.structuralWeight(), duration);
            return requiredSyntax.syntax();
        } catch (CacheBookkeepingException exception) {
            log(key,
                    "BYPASSED_CACHE_FAILURE",
                    false,
                    0,
                    Duration.ofNanos(System.nanoTime() - lookupStartedAt));
            return exception.syntax();
        } catch (RuntimeException exception) {
            log(key, "LOAD_FAILED", false, 0, Duration.ofNanos(System.nanoTime() - lookupStartedAt));
            throw exception;
        }
    }

    private CachedRepositorySyntax load(
            RepositorySnapshot snapshot,
            AtomicReference<CachedRepositorySyntax> loadedSyntax,
            AtomicLong loadDurationNanos) {
        long loadStartedAt = System.nanoTime();
        try {
            RepositorySyntax syntax = Objects.requireNonNull(
                    syntaxExtractionService.extract(snapshot.root()), "syntax extraction result is required");
            CachedRepositorySyntax cachedSyntax;
            try {
                cachedSyntax = new CachedRepositorySyntax(syntax, weightEstimator.estimate(syntax));
            } catch (RuntimeException exception) {
                throw new CacheBookkeepingException(
                        "repository syntax weight estimation failed", syntax, exception);
            }
            loadedSyntax.set(cachedSyntax);
            return cachedSyntax;
        } finally {
            loadDurationNanos.set(System.nanoTime() - loadStartedAt);
        }
    }

    private int cacheWeight(int structuralWeight, int maximumWeight) {
        if (structuralWeight > maximumEntryWeight) {
            return maximumWeight + 1;
        }
        return structuralWeight;
    }

    private void logEviction(
            RepositorySyntaxCacheKey key,
            CachedRepositorySyntax syntax,
            RemovalCause cause) {
        if (Objects.nonNull(key) && Objects.nonNull(syntax) && cause.wasEvicted()) {
            log(key, "EVICTED", false, syntax.structuralWeight(), Duration.ZERO);
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

    /** 將 RepositorySyntax 與 admission 時的一次性結構權重綁定 */
    private record CachedRepositorySyntax(RepositorySyntax syntax, int structuralWeight) {

        private CachedRepositorySyntax {
            syntax = Objects.requireNonNull(syntax, "syntax is required");
            if (structuralWeight < 1) {
                throw new IllegalArgumentException("structuralWeight must be positive");
            }
        }
    }

    /** 區分已成功 extraction 後發生的快取帳務錯誤，僅限方法內控制流程且不得記錄或向外傳播 */
    private static final class CacheBookkeepingException extends RuntimeException {

        private final RepositorySyntax syntax;

        private CacheBookkeepingException(String message, RepositorySyntax syntax, Throwable cause) {
            super(message, cause);
            this.syntax = Objects.requireNonNull(syntax, "syntax is required");
        }

        private RepositorySyntax syntax() {
            return syntax;
        }

        @Override
        public synchronized Throwable fillInStackTrace() {
            return this;
        }
    }
}
