package com.java.semantic.api;

import com.java.semantic.api.dto.ApiErrorResponse;
import com.java.semantic.api.dto.MethodTargetResponse;
import com.java.semantic.identity.MethodTarget;
import com.java.semantic.semantic.domain.SemanticBindingAmbiguousException;
import com.java.semantic.semantic.domain.SemanticBindingUnresolvedException;
import com.java.semantic.semantic.domain.SemanticProtocolException;
import com.java.semantic.semantic.domain.SemanticTargetNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void should_map_semantic_binding_ambiguity_to_a_sorted_conflict_response_with_request_context() {
        MethodTarget requested = target("Requested", "run");
        MethodTarget later = target("Zeta", "run");
        MethodTarget earlier = target("Alpha", "work");
        MockHttpServletRequest request = correlatedRequest();

        ResponseEntity<ApiErrorResponse> response = handler.semanticBindingAmbiguous(
                new SemanticBindingAmbiguousException(requested, List.of(later, earlier)),
                request);
        ApiErrorResponse body = Objects.requireNonNull(response.getBody(), "response body is required");

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(body.errorCode()).isEqualTo("SEMANTIC_BINDING_AMBIGUOUS");
        assertThat(body.requestId()).isEqualTo("request-42");
        assertThat(body.target()).isEqualTo(responseTarget(requested));
        assertThat(body.candidates()).containsExactly(
                responseTarget(earlier),
                responseTarget(later));
    }

    @Test
    void should_map_typed_semantic_target_failures_without_exposing_internal_details() {
        MethodTarget requested = target("Requested", "run");
        MockHttpServletRequest request = correlatedRequest();

        ResponseEntity<ApiErrorResponse> notFound = handler.semanticTargetNotFound(
                new SemanticTargetNotFoundException(requested), request);
        ResponseEntity<ApiErrorResponse> unresolved = handler.semanticBindingUnresolved(
                new SemanticBindingUnresolvedException(requested), request);

        assertThat(notFound.getStatusCode().value()).isEqualTo(422);
        assertThat(Objects.requireNonNull(notFound.getBody(), "not found body is required").errorCode())
                .isEqualTo("SEMANTIC_TARGET_NOT_FOUND");
        assertThat(Objects.requireNonNull(notFound.getBody(), "not found body is required").target())
                .isEqualTo(responseTarget(requested));
        assertThat(unresolved.getStatusCode().value()).isEqualTo(422);
        assertThat(Objects.requireNonNull(unresolved.getBody(), "unresolved body is required").errorCode())
                .isEqualTo("SEMANTIC_BINDING_UNRESOLVED");
        assertThat(new SemanticTargetNotFoundException(requested).target()).isEqualTo(requested);
        assertThat(new SemanticBindingUnresolvedException(requested).target()).isEqualTo(requested);
    }

    @Test
    void should_map_semantic_protocol_failures_to_a_safe_bad_gateway_envelope() {
        ResponseEntity<ApiErrorResponse> response = handler.semanticProtocolError(correlatedRequest());
        ApiErrorResponse body = Objects.requireNonNull(response.getBody(), "response body is required");

        assertThat(response.getStatusCode().value()).isEqualTo(502);
        assertThat(body.errorCode()).isEqualTo(new SemanticProtocolException().errorCode());
        assertThat(body.message()).isEqualTo("semantic protocol request failed");
        assertThat(body.requestId()).isEqualTo("request-42");
        assertThat(body.candidates()).isEmpty();
    }

    private static MockHttpServletRequest correlatedRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("semantic.requestId", "request-42");
        return request;
    }

    private static MethodTargetResponse responseTarget(MethodTarget target) {
        return new MethodTargetResponse(
                target.sourceFile(),
                target.packageName(),
                target.className(),
                target.methodName(),
                target.parameterTypes());
    }

    private static MethodTarget target(String className, String methodName) {
        return new MethodTarget(className + ".java", "com.example", className, methodName, List.of());
    }
}
