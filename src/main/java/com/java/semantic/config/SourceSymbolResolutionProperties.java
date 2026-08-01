package com.java.semantic.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** source-symbol context retry collection 的 response limit */
@Validated
@ConfigurationProperties(prefix = "semantic.discovery.source-symbol-resolution")
public record SourceSymbolResolutionProperties(
        @DefaultValue("100")
        @Min(2)
        int contextCandidateLimit) {
}
