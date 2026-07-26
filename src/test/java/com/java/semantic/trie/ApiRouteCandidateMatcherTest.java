package com.java.semantic.trie;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class ApiRouteCandidateMatcherTest {

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "'static positional', /orders/42, /orders/{*}, SHARED_STATIC_SEGMENT|POSITIONAL_STATIC_SEGMENT|SAME_SEGMENT_COUNT",
            "'same length', /orders/42, /archive/orders, SHARED_STATIC_SEGMENT|SAME_SEGMENT_COUNT",
            "'no static segment', /orders/42, /users/{*}, SAME_SEGMENT_COUNT",
            "'wildcards only', /orders/42, /{*}/{*}, SAME_SEGMENT_COUNT"
    })
    void should_describe_route_candidate_structure(
            String caseName, String route, String candidate, String expectedReasons) {
        assertThat(ApiRouteCandidateMatcher.suggestionReasons(route, candidate))
                .extracting(Enum::name)
                .containsExactly(expectedReasons.split("\\|"));
    }
}
