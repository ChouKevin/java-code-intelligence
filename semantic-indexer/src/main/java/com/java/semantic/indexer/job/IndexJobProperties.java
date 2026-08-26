package com.java.semantic.indexer.job;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "semantic.index-jobs")
public record IndexJobProperties(@DefaultValue("1s") Duration pollDelay) {
    public IndexJobProperties {
        Objects.requireNonNull(pollDelay, "poll delay is required");
        if (pollDelay.isNegative() || pollDelay.isZero()) {
            throw new IllegalArgumentException("poll delay must be positive");
        }
    }
}
