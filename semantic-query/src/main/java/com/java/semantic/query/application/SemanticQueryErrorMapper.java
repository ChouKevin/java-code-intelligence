package com.java.semantic.query.application;

import com.java.semantic.query.source.SourceQueryException;
import java.util.Objects;

/** One safe error body for HTTP and MCP; raw storage/process causes never leave the service. */
public final class SemanticQueryErrorMapper {

    public SemanticQueryError map(RuntimeException exception) {
        Objects.requireNonNull(exception, "source failure");
        if (exception instanceof SourceQueryException source) {
            return new SemanticQueryError(source.code().name(), source.getMessage());
        }
        if (exception instanceof IllegalArgumentException || exception instanceof NullPointerException) {
            return new SemanticQueryError("INVALID_ARGUMENT", "The request is invalid.");
        }
        return new SemanticQueryError("SOURCE_UNAVAILABLE", "The source service is unavailable.");
    }
}
