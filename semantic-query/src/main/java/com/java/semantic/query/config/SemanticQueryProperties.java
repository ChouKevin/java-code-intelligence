package com.java.semantic.query.config;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.Objects;

@Validated
@ConfigurationProperties(prefix = "semantic.query")
public record SemanticQueryProperties(@DefaultValue("2s") @NotNull Duration storageTimeout) {
    public SemanticQueryProperties {
        storageTimeout = Objects.requireNonNull(storageTimeout, "storage timeout is required");
        if (storageTimeout.isNegative() || storageTimeout.isZero()) {
            throw new IllegalArgumentException("storage timeout must be positive");
        }
    }
}
