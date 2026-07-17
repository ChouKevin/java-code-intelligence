package com.java.semantic.repository.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RepositoryRevisionTest {

    @Test
    void should_accept_when_revision_is_a_lowercase_sha() {
        String sha = "0123456789abcdef0123456789abcdef01234567";

        assertThat(RepositoryRevision.ofSha(sha).value()).isEqualTo(sha);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "abc", "FIXTURE", "0123456789ABCDEF0123456789ABCDEF01234567"})
    void should_reject_when_sha_is_not_exact_lowercase_hex(String value) {
        assertThatThrownBy(() -> RepositoryRevision.ofSha(value))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void should_report_non_pinnable_when_revision_is_a_fixture() {
        RepositoryRevision revision = RepositoryRevision.fixture();

        assertThat(revision.value()).isEqualTo("FIXTURE");
        assertThat(revision.isPinnable()).isFalse();
    }
}
