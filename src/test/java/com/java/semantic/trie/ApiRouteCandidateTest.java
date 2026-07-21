package com.java.semantic.trie;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiRouteCandidateTest {

    private static final String REVISION =
            "1111111111111111111111111111111111111111";

    @Test
    void should_preserve_every_field_when_candidate_is_mapped_from_entry_point_ref() {
        ApiEntryPointRef ref = new ApiEntryPointRef(
                "repo-a",
                REVISION,
                "com.example.orders",
                "OrderController",
                "findOrder",
                "GET",
                "/orders/{*}");

        ApiRouteCandidate candidate = ApiRouteCandidate.from(ref);

        assertThat(candidate).isEqualTo(new ApiRouteCandidate(
                "repo-a",
                REVISION,
                "GET",
                "/orders/{*}",
                "com.example.orders",
                "OrderController",
                "findOrder"));
    }
}
