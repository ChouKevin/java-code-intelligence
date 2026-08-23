package com.java.semantic.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.Objects;

/** RepositorySyntax 快取的結構性 metadata 容量與存取期限設定 */
@ConfigurationProperties(prefix = "semantic.syntax.cache")
public record RepositorySyntaxCacheProperties(
        @DefaultValue("10000") int maximumWeight,
        @DefaultValue("1000") int maximumEntryWeight,
        @DefaultValue("10m") Duration expireAfterAccess) {

    public RepositorySyntaxCacheProperties {
        expireAfterAccess = Objects.requireNonNull(expireAfterAccess, "expireAfterAccess is required");
        if (maximumWeight < 1 || maximumWeight == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("maximumWeight must be positive and bounded");
        }
        if (maximumEntryWeight < 1 || maximumEntryWeight > maximumWeight) {
            throw new IllegalArgumentException("maximumEntryWeight must be positive and at most maximumWeight");
        }
        if (expireAfterAccess.isZero() || expireAfterAccess.isNegative()) {
            throw new IllegalArgumentException("expireAfterAccess must be positive");
        }
    }
}
