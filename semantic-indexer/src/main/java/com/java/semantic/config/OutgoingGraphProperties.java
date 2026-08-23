package com.java.semantic.config;

import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** Server-only cap for depth-two local source hydration in outgoing fragments. */
@Validated
@ConfigurationProperties(prefix = "semantic.analysis.outgoing")
public record OutgoingGraphProperties(@DefaultValue("40") @PositiveOrZero int depthTwoNodeBudget) {
}
