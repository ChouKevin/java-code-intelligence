package com.java.semantic.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/** 驗證 RepositorySyntax cache 的容量與存取期限設定 */
class RepositorySyntaxCachePropertiesTest {

    @Test
    void should_require_positive_cache_policy_values_and_allow_an_equal_entry_ceiling() {
        assertThatThrownBy(() -> new RepositorySyntaxCacheProperties(0, 1, Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RepositorySyntaxCacheProperties(10, 1, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RepositorySyntaxCacheProperties(10, 0, Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RepositorySyntaxCacheProperties(10, 11, Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);

        assertDoesNotThrow(() -> new RepositorySyntaxCacheProperties(10, 10, Duration.ofMinutes(1)));
    }
}
