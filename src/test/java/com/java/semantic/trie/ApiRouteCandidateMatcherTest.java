package com.java.semantic.trie;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class ApiRouteCandidateMatcherTest {

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "'static positional', /orders/42, /orders/{*}, 12",
            "'same length', /orders/42, /archive/orders, 7",
            "'no static segment', /orders/42, /users/{*}, -1",
            "'wildcards only', /orders/42, /{*}/{*}, -1"
    })
    void should_score_route_candidate(
            String caseName, String route, String candidate, int expectedScore) {
        assertThat(ApiRouteCandidateMatcher.score(route, candidate)).isEqualTo(expectedScore);
    }
}
