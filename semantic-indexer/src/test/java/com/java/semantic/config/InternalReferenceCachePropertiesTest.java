package com.java.semantic.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InternalReferenceCachePropertiesTest {

    @Test
    void should_require_positive_cache_policy_values_and_a_lower_entry_ceiling() {
        assertThatThrownBy(() -> new InternalReferenceCacheProperties(0, Duration.ofMinutes(1), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InternalReferenceCacheProperties(10, Duration.ZERO, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InternalReferenceCacheProperties(10, Duration.ofMinutes(1), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InternalReferenceCacheProperties(10, Duration.ofMinutes(1), 10))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
