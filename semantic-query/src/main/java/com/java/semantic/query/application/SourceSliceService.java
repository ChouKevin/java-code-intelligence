package com.java.semantic.query.application;

import com.java.semantic.model.codefact.CodeFactDetails;
import com.java.semantic.model.codefact.CodeFactIdentity;
import com.java.semantic.model.codefact.CodeFactReadQuery;
import com.java.semantic.model.codefact.MethodTarget;
import com.java.semantic.model.codefact.PublishedSourceSegment;
import com.java.semantic.model.codefact.SourceRange;
import com.java.semantic.model.codefact.SyntaxPosition;
import com.java.semantic.model.codefact.SyntaxRange;
import com.java.semantic.model.codefact.SourceSegmentQuery;
import com.java.semantic.model.query.CurrentGeneration;

import java.util.Objects;
import java.util.Optional;

/** Materializes only authorized slices from stored source artifacts. */
public final class SourceSliceService {
    private final CurrentSourceQueryService sourceQueryService;
    private final CodeFactReadService codeFactReadService;

    public SourceSliceService(CurrentSourceQueryService sourceQueryService, CodeFactReadService codeFactReadService) {
        this.sourceQueryService = Objects.requireNonNull(sourceQueryService, "source query service is required");
        this.codeFactReadService = Objects.requireNonNull(codeFactReadService, "code fact read service is required");
    }

    public PublishedSourceSegment methodSource(String repositoryId, String revision, CodeFactIdentity identity) {
        CodeFactDetails fact = codeFactReadService.get(repositoryId, revision, identity);
        if (!(identity.canonicalIdentity() instanceof MethodTarget method)) { throw new CodeFactKindUnsupportedException(identity.kind()); }
        String content = sourceQueryService.getSource(fact.generation(), fact.location().sourceFile()).utf8Content();
        return slice(fact.generation(), fact.location(), content, Optional.empty(), 0);
    }

    public PublishedSourceSegment sourceSegment(SourceSegmentQuery query) {
        SourceSegmentQuery required = Objects.requireNonNull(query, "query is required");
        com.java.semantic.query.application.PublishedSource source = sourceQueryService.getSource(required.repositoryId().value(),
                required.revision().value(), required.sourceType());
        return slice(source.generation(), required.range(), source.utf8Content(), Optional.empty(), 0);
    }

    public PublishedSourceSegment evidenceSource(String repositoryId, String revision, CodeFactIdentity identity) {
        CodeFactDetails fact = codeFactReadService.get(repositoryId, revision, identity);
        String content = sourceQueryService.getSource(fact.generation(), fact.location().sourceFile()).utf8Content();
        return slice(fact.generation(), fact.location(), content, Optional.of(fact.fact().identity()), 0);
    }

    public FactSourceSlice factSource(CodeFactReadQuery query, int contextLines) {
        CodeFactReadQuery requiredQuery = Objects.requireNonNull(query, "code fact read query is required");
        if (contextLines < 0 || contextLines > SemanticQueryContract.MAX_CONTEXT_LINES) {
            throw new IllegalArgumentException("context lines must be between 0 and " + SemanticQueryContract.MAX_CONTEXT_LINES);
        }
        CodeFactDetails fact = codeFactReadService.get(requiredQuery);
        String content = sourceQueryService.getSource(fact).utf8Content();
        SourceRange sourceRange = expandedRange(fact.location(), content, contextLines);
        return new FactSourceSlice(fact.generation(), sourceRange, fact.location(), content);
    }

    private static SourceRange expandedRange(SourceRange factRange, String content, int contextLines) {
        int start = offset(content, factRange.range().start());
        int end = offset(content, factRange.range().end());
        if (start >= end) { throw new IndexContractMismatchException(); }
        if (contextLines == 0) {
            return factRange;
        }
        int firstLine = Math.max(0, factRange.range().start().line() - contextLines);
        int finalFactLine = factRange.range().end().character() == 0 && factRange.range().end().line() > factRange.range().start().line()
                ? factRange.range().end().line() - 1 : factRange.range().end().line();
        int finalLine = Math.min(lineCount(content) - 1, finalFactLine + contextLines);
        SyntaxPosition endPosition = positionAfterLine(content, finalLine);
        return new SourceRange(factRange.sourceFile(), new SyntaxRange(
                new SyntaxPosition(firstLine, 0), endPosition));
    }

    private static PublishedSourceSegment slice(CurrentGeneration generation, SourceRange location, String content,
                                                Optional<CodeFactIdentity> evidenceIdentity, int contextLines) {
        int start = offset(content, location.range().start());
        int end = offset(content, location.range().end());
        int contextStart = contextStart(content, start, contextLines);
        int contextEnd = contextEnd(content, end, contextLines);
        return new PublishedSourceSegment(generation, location, content.substring(contextStart, contextEnd), evidenceIdentity,
                Optional.empty(), contextStart != start || contextEnd != end);
    }

    private static int offset(String content, SyntaxPosition position) {
        int lineStart = 0;
        for (int line = 0; line < position.line(); line++) {
            int newline = content.indexOf('\n', lineStart);
            if (newline < 0) { throw new IndexContractMismatchException(); }
            lineStart = newline + 1;
        }
        int newline = content.indexOf('\n', lineStart);
        int lineEnd = newline < 0 ? content.length() : newline;
        if (lineEnd > lineStart && content.charAt(lineEnd - 1) == '\r') {
            lineEnd--;
        }
        int logicalLineLength = lineEnd - lineStart;
        if (position.character() > logicalLineLength) { throw new IndexContractMismatchException(); }
        return lineStart + position.character();
    }

    private static int contextStart(String content, int offset, int lines) {
        int result = offset;
        for (int count = 0; count < lines; count++) {
            int previous = content.lastIndexOf('\n', Math.max(0, result - 2));
            if (previous < 0) { return 0; }
            result = previous + 1;
        }
        return result;
    }

    private static int contextEnd(String content, int offset, int lines) {
        int result = offset;
        for (int count = 0; count < lines; count++) {
            int newline = content.indexOf('\n', result);
            if (newline < 0) { return content.length(); }
            result = newline + 1;
        }
        return result;
    }

    private static int lineCount(String content) {
        int result = 1;
        for (int index = 0; index < content.length(); index++) {
            if (content.charAt(index) == '\n') {
                result++;
            }
        }
        return result;
    }

    private static SyntaxPosition positionAfterLine(String content, int line) {
        int totalLines = lineCount(content);
        if (line < totalLines - 1) {
            return new SyntaxPosition(line + 1, 0);
        }
        int start = 0;
        for (int index = 0; index < line; index++) {
            int newline = content.indexOf('\n', start);
            if (newline < 0) { throw new IndexContractMismatchException(); }
            start = newline + 1;
        }
        int end = content.length();
        if (end > start && content.charAt(end - 1) == '\r') {
            end--;
        }
        return new SyntaxPosition(line, end - start);
    }
}
