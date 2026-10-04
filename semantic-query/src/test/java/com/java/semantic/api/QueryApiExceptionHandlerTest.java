package com.java.semantic.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.java.semantic.query.application.SemanticQueryError;
import com.java.semantic.query.source.SourceQueryException;
import com.java.semantic.query.source.SourceQueryException.Code;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class QueryApiExceptionHandlerTest {
    @Test
    void source_failures_preserve_each_safe_http_status_without_exposing_causes() {
        QueryApiExceptionHandler handler = new QueryApiExceptionHandler();
        Map<Code, HttpStatus> statuses = Map.of(
                Code.INVALID_ARGUMENT, HttpStatus.BAD_REQUEST,
                Code.REPOSITORY_NOT_FOUND, HttpStatus.NOT_FOUND,
                Code.SOURCE_NOT_PREPARED, HttpStatus.CONFLICT,
                Code.REVISION_NOT_PREPARED, HttpStatus.NOT_FOUND,
                Code.SOURCE_NOT_FOUND, HttpStatus.NOT_FOUND,
                Code.SOURCE_UNSUPPORTED, HttpStatus.UNPROCESSABLE_ENTITY,
                Code.SOURCE_BUSY, HttpStatus.SERVICE_UNAVAILABLE,
                Code.SOURCE_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE,
                Code.SOURCE_TIMEOUT, HttpStatus.GATEWAY_TIMEOUT);
        for (Map.Entry<Code, HttpStatus> expected : statuses.entrySet()) {
            ResponseEntity<SemanticQueryError> response = handler.failure(new SourceQueryException(expected.getKey(),
                    new IllegalStateException("/private/source-admin/credentials")));
            assertThat(response.getStatusCode()).isEqualTo(expected.getValue());
            assertThat(response.getBody().code()).isEqualTo(expected.getKey().name());
            assertThat(response.getBody().message()).doesNotContain("private", "credentials");
        }
    }
}
