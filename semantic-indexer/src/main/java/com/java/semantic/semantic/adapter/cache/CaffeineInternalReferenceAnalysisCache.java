package com.java.semantic.semantic.adapter.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import com.java.semantic.config.InternalReferenceCacheProperties;
import com.java.semantic.semantic.application.InternalReferenceAnalysis;
import com.java.semantic.semantic.application.InternalReferenceAnalysisCache;
import com.java.semantic.semantic.application.InternalReferenceStatus;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** 以 Caffeine 提供 same-key single-flight 與 revision-bound 加權淘汰 */
public final class CaffeineInternalReferenceAnalysisCache implements InternalReferenceAnalysisCache {

    private final Cache<Key, InternalReferenceAnalysis> cache;
    private final int maximumEntryWeight;

    public CaffeineInternalReferenceAnalysisCache(InternalReferenceCacheProperties properties) {
        this(properties, Ticker.systemTicker());
    }

    CaffeineInternalReferenceAnalysisCache(
            InternalReferenceCacheProperties properties, Ticker ticker) {
        InternalReferenceCacheProperties requiredProperties = Objects.requireNonNull(
                properties, "properties is required");
        Ticker requiredTicker = Objects.requireNonNull(ticker, "ticker is required");
        this.maximumEntryWeight = requiredProperties.maximumEntryWeight();
        this.cache = Caffeine.newBuilder()
                .maximumWeight(requiredProperties.maximumWeight())
                .expireAfterAccess(requiredProperties.expireAfterAccess())
                .ticker(requiredTicker)
                .weigher((Key ignored, InternalReferenceAnalysis analysis) -> analysis.entryWeight())
                .build();
    }

    @Override
    public LookupResult lookup(Key key, Supplier<InternalReferenceAnalysis> loader) {
        Key requiredKey = Objects.requireNonNull(key, "key is required");
        Supplier<InternalReferenceAnalysis> requiredLoader = Objects.requireNonNull(loader, "loader is required");
        InternalReferenceAnalysis existingValue = cache.getIfPresent(requiredKey);
        if (Objects.nonNull(existingValue)) {
            return new LookupResult(
                    existingValue,
                    true,
                    true,
                    existingValue.entryWeight(),
                    Duration.ZERO);
        }
        AtomicReference<InternalReferenceAnalysis> loadedValue = new AtomicReference<>();
        AtomicLong loadDurationNanos = new AtomicLong();
        long lookupStartedAt = System.nanoTime();
        InternalReferenceAnalysis admittedValue = cache.get(requiredKey, ignored -> {
            long startedAt = System.nanoTime();
            InternalReferenceAnalysis value;
            try {
                value = Objects.requireNonNull(requiredLoader.get(), "loader result is required");
            } finally {
                loadDurationNanos.set(System.nanoTime() - startedAt);
            }
            loadedValue.set(value);
            return eligible(value) ? value : null;
        });
        long lookupDurationNanos = System.nanoTime() - lookupStartedAt;
        InternalReferenceAnalysis computedValue = loadedValue.get();
        boolean loaderOwner = Objects.nonNull(computedValue);
        InternalReferenceAnalysis value = Objects.nonNull(admittedValue)
                ? admittedValue
                : Objects.requireNonNull(computedValue, "cache lookup produced no value");
        boolean stored = eligible(value) && Objects.equals(cache.getIfPresent(requiredKey), value);
        return new LookupResult(
                value,
                false,
                stored,
                value.entryWeight(),
                Duration.ofNanos(loaderOwner ? loadDurationNanos.get() : lookupDurationNanos));
    }

    private boolean eligible(InternalReferenceAnalysis analysis) {
        return InternalReferenceStatus.COMPLETE.equals(analysis.status())
                && analysis.entryWeight() <= maximumEntryWeight;
    }
}
