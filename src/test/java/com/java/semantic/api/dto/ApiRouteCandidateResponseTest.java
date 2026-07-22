package com.java.semantic.api.dto;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiRouteCandidateResponseTest {

    @Test
    void should_reject_missing_analysis_target() {
        assertThatThrownBy(() -> new ApiRouteCandidateResponse(
                "repo",
                "revision",
                "GET",
                "/orders",
                "com.example",
                "OrderController",
                "find",
                null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("analysisTarget is required");
    }

    @Test
    void should_reject_missing_resolution_envelope_values() {
        assertThatThrownBy(() -> new MethodTargetResolutionResponse(
                null, null, List.of(), ""))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("status is required");
        assertThatThrownBy(() -> new MethodTargetResolutionResponse(
                "RESOLVED", null, List.of(), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("reasonCode is required");
        assertThatThrownBy(() -> new MethodTargetResolutionResponse(
                "RESOLVED", null, null, ""))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("candidates are required");
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
