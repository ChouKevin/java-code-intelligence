package com.java.semantic.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * 方法實作探索回應的硬上限設定
 * endpoint 在單一 fixed revision request 中完成 in-memory discovery，無 stable continuation snapshot，故不提供 pagination，caller 必須看 truncated
 */
@Validated
@ConfigurationProperties(prefix = "semantic.discovery.method-implementations")
public record ImplementationDiscoveryProperties(
        @DefaultValue("100")
        @Min(1)
        @Max(1000)
        int candidateLimit) {
}
