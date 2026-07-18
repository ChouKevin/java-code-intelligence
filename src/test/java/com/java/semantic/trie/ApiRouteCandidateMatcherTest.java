package com.java.semantic.trie;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiRouteCandidateMatcherTest {

    @Test
    void should_score_static_positional_and_same_length_matches_when_route_is_similar() {
        assertThat(ApiRouteCandidateMatcher.score("/orders/42", "/orders/{*}"))
                .isEqualTo(12);
        assertThat(ApiRouteCandidateMatcher.score("/orders/42", "/archive/orders"))
                .isEqualTo(7);
    }

    @Test
    void should_reject_candidate_when_route_has_no_static_match() {
        assertThat(ApiRouteCandidateMatcher.score("/orders/42", "/users/{*}"))
                .isEqualTo(-1);
        assertThat(ApiRouteCandidateMatcher.score("/orders/42", "/{*}/{*}"))
                .isEqualTo(-1);
    }
}
