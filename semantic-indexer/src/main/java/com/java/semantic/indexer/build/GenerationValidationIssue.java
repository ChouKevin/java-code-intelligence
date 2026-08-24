package com.java.semantic.indexer.build;

import java.util.Objects;

/** Safe, stable validation result; detail intentionally never contains source or connection material. */
public record GenerationValidationIssue(String code, String detail) {
    public GenerationValidationIssue {
        code = Objects.requireNonNull(code, "code is required");
        detail = Objects.requireNonNull(detail, "detail is required");
    }
}
