package com.java.semantic.api;

import com.java.semantic.api.dto.ApiErrorResponse;
import com.java.semantic.semantic.domain.SemanticAmbiguousMethodException;
import com.java.semantic.semantic.domain.SemanticSymbolNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void should_map_ambiguous_method_to_conflict_with_candidates() {
        SemanticAmbiguousMethodException exception = new SemanticAmbiguousMethodException(
                "com.example", "OrderService", "save", List.of("save(Order)", "save(Long)"));

        ResponseEntity<ApiErrorResponse> response = handler.ambiguousMethod(exception);
        ApiErrorResponse body = Objects.requireNonNull(response.getBody(), "response body is required");

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(body.errorCode()).isEqualTo("SEMANTIC_AMBIGUOUS_METHOD");
        assertThat(body.candidates()).contains(List.of("save(Order)", "save(Long)"));
    }

    @Test
    void should_map_symbol_not_found_to_unprocessable_entity() {
        ResponseEntity<ApiErrorResponse> response = handler.symbolNotFound();
        ApiErrorResponse body = Objects.requireNonNull(response.getBody(), "response body is required");

        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(body.errorCode()).isEqualTo("SEMANTIC_SYMBOL_NOT_FOUND");
    }

    @Test
    void should_not_map_symbol_not_found_exception_type_directly_but_via_handler() {
        SemanticSymbolNotFoundException exception =
                new SemanticSymbolNotFoundException("type not found");

        assertThat(exception).hasMessage("type not found");
    }
}
