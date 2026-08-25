package com.java.semantic.query.application;

import com.java.semantic.model.codefact.CodeFactDetails;
import com.java.semantic.model.codefact.CodeFactIdentity;
import com.java.semantic.model.codefact.MethodTarget;
import com.java.semantic.model.codefact.PublishedSourceSegment;
import com.java.semantic.model.codefact.SourceRange;
import com.java.semantic.model.codefact.SyntaxPosition;
import com.java.semantic.model.codefact.SourceSegmentQuery;
import com.java.semantic.model.query.CurrentGeneration;

import java.util.Objects;
import java.util.Optional;

/** Materializes only authorized slices from stored source artifacts. */
public final class PublishedSourceToolService {
    private final CurrentSourceQueryService sourceQueryService;
    private final CodeFactReadService codeFactReadService;

    public PublishedSourceToolService(CurrentSourceQueryService sourceQueryService, CodeFactReadService codeFactReadService) {
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

    public PublishedSourceSegment sourceSegment(String repositoryId, String revision, CodeFactIdentity identity, int contextLines) {
        CodeFactDetails fact = codeFactReadService.get(repositoryId, revision, identity);
        if (contextLines < 0 || contextLines > 20) { throw new IllegalArgumentException("context lines must be between 0 and 20"); }
        Optional<MethodTarget> method = identity.canonicalIdentity() instanceof MethodTarget target
                ? Optional.of(target) : Optional.empty();
        if (method.isEmpty()) { throw new CodeFactKindUnsupportedException(identity.kind()); }
        String content = sourceQueryService.getSource(fact.generation(), fact.location().sourceFile()).utf8Content();
        return slice(fact.generation(), fact.location(), content, Optional.empty(), contextLines);
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
}
