package com.java.semantic.query.application;

import com.java.semantic.model.index.IndexFailure;
import com.java.semantic.model.index.IndexFailure.Code;
import com.java.semantic.query.source.SourceQueryException;
import java.util.Objects;

/** One safe error body for HTTP and MCP; raw storage/process causes never leave the service. */
public final class SemanticQueryErrorMapper {

    public SemanticQueryError map(RuntimeException exception) {
        Objects.requireNonNull(exception, "query failure");
        if (exception instanceof IndexFailure index) {
            Code code = index.code();
            return new SemanticQueryError(code.name(), code.advice(), java.util.Optional.empty());
        }
        if (exception instanceof SourceQueryException source) {
            return new SemanticQueryError(source.code().name(), source.getMessage(), source.reason());
        }
        if (exception instanceof IllegalArgumentException || exception instanceof NullPointerException) {
            return new SemanticQueryError("INVALID_ARGUMENT",
                    "Use the exact repositoryId/revision context and documented fields. search_code requires match exact or prefix; use search_text for literal body/contains search. Branch names, regex options and mismatched cursors are not accepted.", java.util.Optional.empty());
        }
        return new SemanticQueryError("SOURCE_UNAVAILABLE", "The source service is unavailable.", java.util.Optional.empty());
    }
}
