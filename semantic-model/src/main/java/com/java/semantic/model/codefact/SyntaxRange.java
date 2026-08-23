package com.java.semantic.model.codefact;

import com.java.semantic.model.support.ModelValidation;

import java.util.Objects;

public record SyntaxRange(SyntaxPosition start, SyntaxPosition end) {

    public SyntaxRange {
        start = Objects.requireNonNull(start, "start is required");
        end = Objects.requireNonNull(end, "end is required");
        ModelValidation.require(start.compareTo(end) <= 0, "range end must not precede start");
    }
}
