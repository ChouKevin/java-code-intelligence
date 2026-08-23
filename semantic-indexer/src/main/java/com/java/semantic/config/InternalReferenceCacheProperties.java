package com.java.semantic.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.Objects;

/** 內部 reference 完整分析 cache 的正值容量與存取期限 */
@ConfigurationProperties(prefix = "semantic.discovery.internal-references.cache")
public record InternalReferenceCacheProperties(
        @DefaultValue("10000") int maximumWeight,
        @DefaultValue("10m") Duration expireAfterAccess,
        @DefaultValue("1000") int maximumEntryWeight) {

    public InternalReferenceCacheProperties {
        expireAfterAccess = Objects.requireNonNull(expireAfterAccess, "expireAfterAccess is required");
        if (maximumWeight < 1 || maximumWeight == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("maximumWeight must be positive and bounded");
        }
        if (expireAfterAccess.isZero() || expireAfterAccess.isNegative()) {
            throw new IllegalArgumentException("expireAfterAccess must be positive");
        }
        if (maximumEntryWeight < 1 || maximumEntryWeight >= maximumWeight) {
            throw new IllegalArgumentException("maximumEntryWeight must be positive and lower than maximumWeight");
        }
    }
}
