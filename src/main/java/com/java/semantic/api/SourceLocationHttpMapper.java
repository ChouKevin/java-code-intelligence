package com.java.semantic.api;

import com.java.semantic.api.dto.location.PositionPayload;
import com.java.semantic.api.dto.location.SourceRangePayload;
import com.java.semantic.api.dto.location.TextRangePayload;
import com.java.semantic.callgraph.domain.CallSiteRange;
import com.java.semantic.semantic.domain.SemanticPosition;
import com.java.semantic.semantic.domain.SemanticRange;
import com.java.semantic.syntax.application.SourceRange;
import com.java.semantic.syntax.domain.SyntaxPosition;
import com.java.semantic.syntax.domain.SyntaxRange;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** 集中投影語法、語意與呼叫點位置至 HTTP 共用 payload */
@Component
public final class SourceLocationHttpMapper {

    public PositionPayload toPayload(SyntaxPosition position) {
        SyntaxPosition source = Objects.requireNonNull(position, "position is required");
        return new PositionPayload(source.line(), source.character());
    }

    public PositionPayload toPayload(SemanticPosition position) {
        SemanticPosition source = Objects.requireNonNull(position, "position is required");
        return new PositionPayload(source.line(), source.character());
    }

    public TextRangePayload toTextRange(SyntaxRange range) {
        SyntaxRange source = Objects.requireNonNull(range, "range is required");
        return new TextRangePayload(toPayload(source.start()), toPayload(source.end()));
    }

    public TextRangePayload toTextRange(SemanticRange range) {
        SemanticRange source = Objects.requireNonNull(range, "range is required");
        return new TextRangePayload(toPayload(source.start()), toPayload(source.end()));
    }

    public TextRangePayload toTextRange(CallSiteRange range) {
        CallSiteRange source = Objects.requireNonNull(range, "range is required");
        return new TextRangePayload(
                new PositionPayload(source.startLine(), source.startCharacter()),
                new PositionPayload(source.endLine(), source.endCharacter()));
    }

    public TextRangePayload toTextRange(SourceRange range) {
        SourceRange source = Objects.requireNonNull(range, "range is required");
        return toTextRange(source.range());
    }

    public SourceRangePayload toSourceRange(CallSiteRange range) {
        CallSiteRange source = Objects.requireNonNull(range, "range is required");
        return new SourceRangePayload(source.sourceFile(), toTextRange(source));
    }

    public SourceRangePayload toSourceRange(SourceRange range) {
        SourceRange source = Objects.requireNonNull(range, "range is required");
        return new SourceRangePayload(source.sourceFile(), toTextRange(source.range()));
    }

    public SyntaxPosition toSyntaxPosition(PositionPayload position) {
        PositionPayload source = Objects.requireNonNull(position, "position is required");
        return new SyntaxPosition(source.line(), source.character());
    }

    public SyntaxRange toSyntaxRange(TextRangePayload range) {
        TextRangePayload source = Objects.requireNonNull(range, "range is required");
        return new SyntaxRange(toSyntaxPosition(source.start()), toSyntaxPosition(source.end()));
    }

    public SourceRange toSourceRange(SourceRangePayload range) {
        SourceRangePayload source = Objects.requireNonNull(range, "range is required");
        return new SourceRange(source.sourceFile(), toSyntaxRange(source.range()));
    }
}
