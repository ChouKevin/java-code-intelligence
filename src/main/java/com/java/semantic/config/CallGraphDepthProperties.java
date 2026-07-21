package com.java.semantic.config;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "entry-point")
public record CallGraphDepthProperties(
        @DefaultValue("3") @Positive int callGraphDepth) {
}
