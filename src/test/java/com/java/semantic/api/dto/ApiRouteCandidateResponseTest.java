package com.java.semantic.api.dto;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiRouteCandidateResponseTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("missingRequiredValues")
    void should_reject_each_required_api_route_candidate_resolution_value(
            String label,
            Executable action) {
        assertThatThrownBy(action::execute).isInstanceOf(NullPointerException.class);
    }

    private static Stream<Arguments> missingRequiredValues() {
        return Stream.of(
                Arguments.of("missing analysis target", (Executable) () -> new ApiRouteCandidateResponse(
                        "repo", "revision", "GET", "/orders", "com.example", "OrderController", "find", null)),
                Arguments.of("missing status", (Executable) () -> new MethodTargetResolutionResponse(
                        null, null, List.of(), "")),
                Arguments.of("missing reason code", (Executable) () -> new MethodTargetResolutionResponse(
                        "RESOLVED", null, List.of(), null)),
                Arguments.of("missing candidates", (Executable) () -> new MethodTargetResolutionResponse(
                        "RESOLVED", null, null, "")));
    }

    @Test
    void should_defensively_copy_resolution_candidates() {
        List<MethodTargetResponse> candidates = new ArrayList<>();
        MethodTargetResolutionResponse response = new MethodTargetResolutionResponse(
                "AMBIGUOUS", null, candidates, "OVERLOAD_AMBIGUOUS");

        candidates.add(new MethodTargetResponse(
                "src/main/java/com/example/OrderController.java",
                "com.example",
                "OrderController",
                "find",
                List.of("java.lang.String")));

        assertThat(response.candidates()).isEmpty();
    }
}
