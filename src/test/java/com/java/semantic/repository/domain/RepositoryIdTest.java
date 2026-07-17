package com.java.semantic.repository.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RepositoryIdTest {

    @ParameterizedTest
    @ValueSource(strings = {"test-repo", "bonus-service", "a", "repo.v2", "order_checkout", "x9"})
    void should_accept_when_id_matches_the_conventional_naming(String value) {
        assertThat(RepositoryId.of(value).value()).isEqualTo(value);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "..", ".", "../x", "../../etc", "/etc/passwd", ".hidden", "-lead", "_lead",
            "UPPER", "has space", "has/slash", "has\\backslash", "trailing\n", "%2e%2e%2f"})
    void should_reject_when_id_could_escape_or_is_unconventional(String value) {
        assertThatThrownBy(() -> RepositoryId.of(value))
                .isInstanceOf(InvalidRepositoryIdException.class);
    }

    @Test
    void should_reject_when_id_is_blank_or_null() {
        assertThatThrownBy(() -> RepositoryId.of("")).isInstanceOf(InvalidRepositoryIdException.class);
        assertThatThrownBy(() -> RepositoryId.of("   ")).isInstanceOf(InvalidRepositoryIdException.class);
        assertThatThrownBy(() -> RepositoryId.of(null)).isInstanceOf(InvalidRepositoryIdException.class);
    }

    @Test
    void should_enforce_the_length_cap_when_id_reaches_the_boundary() {
        assertThat(RepositoryId.of("a".repeat(64)).value()).hasSize(64);
        assertThatThrownBy(() -> RepositoryId.of("a".repeat(65)))
                .isInstanceOf(InvalidRepositoryIdException.class);
    }

    @Test
    void should_reject_when_id_contains_a_null_byte() {
        assertThatThrownBy(() -> RepositoryId.of("a\0b"))
                .isInstanceOf(InvalidRepositoryIdException.class);
    }
}
