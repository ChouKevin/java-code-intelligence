package com.java.semantic.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** exact content inline 與 segment 的 UTF-8 位元組上限組態 */
@Validated
@ConfigurationProperties(prefix = "semantic.discovery.exact-content")
public record ExactContentProperties(
        @DefaultValue("32768") @Min(1024) @Max(65536) int inlineUtf8Bytes,
        @DefaultValue("32768") @Min(1024) @Max(65536) int segmentUtf8Bytes) {
}
