package com.java.semantic.query.application;

import com.java.semantic.model.codefact.SourceRange;
import com.java.semantic.model.codefact.SyntaxPosition;

import java.util.Objects;

public final class SourceSnippetMapper {

    private SourceSnippetMapper() {
    }

    public static SemanticQueryContract.SourceSnippet toSnippet(SourceRange range, String source) {
        SourceRange requiredRange = Objects.requireNonNull(range, "source range is required");
        String requiredSource = Objects.requireNonNull(source, "source is required");
        int startOffset = offset(requiredSource, requiredRange.range().start());
        int endOffset = offset(requiredSource, requiredRange.range().end());
        requireNonEmpty(startOffset, endOffset);
        SemanticQueryContract.FactRange factRange = toFactRange(requiredRange);
        return new SemanticQueryContract.SourceSnippet(requiredRange.sourceFile(), factRange.startLine(), factRange.endLine(),
                requiredSource.substring(startOffset, endOffset));
    }

    public static SemanticQueryContract.FactRange toFactRange(SourceRange range) {
        SourceRange requiredRange = Objects.requireNonNull(range, "source range is required");
        SyntaxPosition start = requiredRange.range().start();
        SyntaxPosition end = requiredRange.range().end();
        requireNonEmpty(start, end);
        int startLine = start.line() + 1;
        int endLine = end.character() == 0 && end.line() > start.line() ? end.line() : end.line() + 1;
        return new SemanticQueryContract.FactRange(startLine, endLine);
    }

    private static int offset(String source, SyntaxPosition position) {
        int lineStart = 0;
        for (int line = 0; line < position.line(); line++) {
            int newline = source.indexOf('\n', lineStart);
            if (newline < 0) {
                throw new IndexContractMismatchException();
            }
            lineStart = newline + 1;
        }
        int newline = source.indexOf('\n', lineStart);
        int lineEnd = newline < 0 ? source.length() : newline;
        if (lineEnd > lineStart && source.charAt(lineEnd - 1) == '\r') {
            lineEnd--;
        }
        if (position.character() > lineEnd - lineStart) {
            throw new IndexContractMismatchException();
        }
        return lineStart + position.character();
    }

    private static void requireNonEmpty(int startOffset, int endOffset) {
        if (startOffset >= endOffset) {
            throw new IndexContractMismatchException();
        }
    }

    private static void requireNonEmpty(SyntaxPosition start, SyntaxPosition end) {
        if (start.compareTo(end) >= 0) {
            throw new IndexContractMismatchException();
        }
    }
}
