package com.java.semantic.model.codefact;

import com.java.semantic.model.support.ModelValidation;

/** Zero-based UTF-16 code-unit position. */
public record TextPosition(int line, int character) implements Comparable<TextPosition> {

    public TextPosition {
        ModelValidation.require(line >= 0, "line must not be negative");
        ModelValidation.require(character >= 0, "character must not be negative");
    }

    @Override
    public int compareTo(TextPosition other) {
        int lineComparison = Integer.compare(line, other.line);
        return lineComparison != 0 ? lineComparison : Integer.compare(character, other.character);
    }
}
