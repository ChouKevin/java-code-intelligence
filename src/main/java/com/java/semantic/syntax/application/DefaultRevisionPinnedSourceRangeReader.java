package com.java.semantic.syntax.application;

import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.repository.domain.RepositorySourceContainment;
import com.java.semantic.repository.domain.RepositorySourceContainmentResult;
import com.java.semantic.syntax.domain.RevisionPinnedSourceRangeReader;
import com.java.semantic.syntax.domain.SourceRange;
import com.java.semantic.syntax.domain.SourceRangeSegment;
import com.java.semantic.syntax.domain.SyntaxPosition;
import com.java.semantic.syntax.domain.SyntaxRange;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 在已固定 snapshot 內安全讀取並限制 canonical 原始碼續讀區段 */
public final class DefaultRevisionPinnedSourceRangeReader implements RevisionPinnedSourceRangeReader {

    static final int MAX_UTF8_BYTES = 64 * 1024;

    private final RepositorySourceContainment containment;

    public DefaultRevisionPinnedSourceRangeReader(RepositorySourceContainment containment) {
        this.containment = Objects.requireNonNull(containment, "containment is required");
    }

    @Override
    public SourceRangeSegment read(
            RepositorySnapshot snapshot,
            SourceRange location,
            int contextLines,
            SourceRange authority) {
        RepositorySnapshot requiredSnapshot = Objects.requireNonNull(snapshot, "snapshot is required");
        SourceRange requiredLocation = Objects.requireNonNull(location, "location is required");
        SourceRange requiredAuthority = Objects.requireNonNull(authority, "authority is required");
        requireContextLines(contextLines);
        Path candidate = requiredSnapshot.root().resolve(requiredLocation.sourceFile());
        RepositorySourceContainmentResult classification = containment.classify(requiredSnapshot.root(), candidate);
        if (!(classification instanceof RepositorySourceContainmentResult.ContainedSource contained)) {
            throw new SourceSegmentNotFoundException();
        }
        if (!contained.sourceFile().equals(requiredLocation.sourceFile())) {
            throw new SourceSegmentNotFoundException();
        }
        String content = readSource(contained.realPath());
        SourceText source = SourceText.from(content);
        require(requiredLocation.sourceFile().equals(requiredAuthority.sourceFile()),
                "authority source file must match location");
        int exactStart = source.offset(requiredLocation.range().start());
        int exactEnd = source.offset(requiredLocation.range().end());
        int authorityStart = source.offset(requiredAuthority.range().start());
        int authorityEnd = source.offset(requiredAuthority.range().end());
        require(exactStart >= authorityStart && exactEnd <= authorityEnd,
                "source location must remain within authority");
        require(exactEnd >= exactStart, "source range end must not precede start");
        requireCodePointBoundary(content, exactStart);
        requireCodePointBoundary(content, exactEnd);
        ExpandedRange expandedRange = expandContext(
                source, requiredLocation.range(), exactStart, exactEnd, authorityStart, authorityEnd, contextLines);
        SourceRange effectiveLocation = new SourceRange(requiredLocation.sourceFile(), expandedRange.range());
        if (utf8Bytes(content, expandedRange.start(), expandedRange.end()) > MAX_UTF8_BYTES) {
            return boundedExactSegment(
                    source,
                    content,
                    effectiveLocation,
                    expandedRange.start(),
                    expandedRange.end(),
                    expandedRange.contextTruncated());
        }
        return new SourceRangeSegment(
                effectiveLocation,
                content.substring(expandedRange.start(), expandedRange.end()),
                Optional.empty(),
                expandedRange.contextTruncated());
    }

    private SourceRangeSegment boundedExactSegment(
            SourceText source,
            String content,
            SourceRange location,
            int exactStart,
            int exactEnd,
            boolean contextTruncated) {
        int segmentEnd = boundedEnd(content, exactStart, exactEnd);
        SyntaxRange segmentRange = new SyntaxRange(location.range().start(), source.position(segmentEnd));
        Optional<SourceRange> nextLocation = segmentEnd < exactEnd
                ? Optional.of(new SourceRange(
                        location.sourceFile(), new SyntaxRange(source.position(segmentEnd), location.range().end())))
                : Optional.empty();
        return new SourceRangeSegment(
                new SourceRange(location.sourceFile(), segmentRange),
                content.substring(exactStart, segmentEnd),
                nextLocation,
                contextTruncated);
    }

    private ExpandedRange expandContext(
            SourceText source,
            SyntaxRange location,
            int exactStart,
            int exactEnd,
            int authorityStart,
            int authorityEnd,
            int contextLines) {
        int requestedBefore = contextLines;
        int requestedAfter = contextLines;
        int before = Math.min(requestedBefore, location.start().line());
        int after = Math.min(requestedAfter, source.lastLineIndex() - location.end().line());
        int startLine = location.start().line() - before;
        int endLine = location.end().line() + after;
        int expandedStart = before > 0 ? source.line(startLine).start() : exactStart;
        int expandedEnd = after > 0 ? source.line(endLine).contentEnd() : exactEnd;
        int start = Math.max(expandedStart, authorityStart);
        int end = Math.min(expandedEnd, authorityEnd);
        SyntaxPosition rangeStart = source.position(start);
        SyntaxPosition rangeEnd = source.position(end);
        return new ExpandedRange(
                new SyntaxRange(rangeStart, rangeEnd),
                start,
                end,
                before < requestedBefore || after < requestedAfter || start != expandedStart || end != expandedEnd);
    }

    private int boundedEnd(String content, int start, int end) {
        int byteCount = 0;
        int index = start;
        while (index < end) {
            int unitEnd = nextUnitEnd(content, index, end);
            int unitBytes = utf8Bytes(content, index, unitEnd);
            if (byteCount + unitBytes > MAX_UTF8_BYTES) {
                break;
            }
            byteCount += unitBytes;
            index = unitEnd;
        }
        require(index > start, "a source segment must make progress");
        return index;
    }

    private int nextUnitEnd(String content, int index, int end) {
        char character = content.charAt(index);
        if (character == '\r' && index + 1 < end && content.charAt(index + 1) == '\n') {
            return index + 2;
        }
        int codePoint = content.codePointAt(index);
        return index + Character.charCount(codePoint);
    }

    private String readSource(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            if (!Files.exists(path)) {
                throw new SourceSegmentNotFoundException();
            }
            throw new UncheckedIOException("Failed to read source segment", exception);
        }
    }

    private int utf8Bytes(String content) {
        return content.getBytes(StandardCharsets.UTF_8).length;
    }

    private int utf8Bytes(String content, int start, int end) {
        return utf8Bytes(content.substring(start, end));
    }

    private void requireCodePointBoundary(String content, int offset) {
        boolean withinContent = offset > 0 && offset < content.length();
        boolean splitsSurrogatePair = withinContent
                && Character.isHighSurrogate(content.charAt(offset - 1))
                && Character.isLowSurrogate(content.charAt(offset));
        require(!splitsSurrogatePair, "source range must not split a Unicode code point");
    }

    private void requireContextLines(int contextLines) {
        require(contextLines >= 0 && contextLines <= 20, "contextLines must be between 0 and 20");
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    private record ExpandedRange(SyntaxRange range, int start, int end, boolean contextTruncated) {
    }

    private record SourceLine(int start, int contentEnd) {

        private int contentLength() {
            return contentEnd - start;
        }
    }

    private record SourceText(List<SourceLine> lines) {

        private SourceText {
            lines = List.copyOf(lines);
        }

        private static SourceText from(String content) {
            List<SourceLine> lines = new ArrayList<>();
            int lineStart = 0;
            int index = 0;
            while (index < content.length()) {
                char character = content.charAt(index);
                if (character == '\r' || character == '\n') {
                    lines.add(new SourceLine(lineStart, index));
                    if (character == '\r' && index + 1 < content.length() && content.charAt(index + 1) == '\n') {
                        index++;
                    }
                    lineStart = index + 1;
                }
                index++;
            }
            lines.add(new SourceLine(lineStart, content.length()));
            return new SourceText(lines);
        }

        private int offset(SyntaxPosition position) {
            if (position.line() >= lines.size()) {
                throw new IllegalArgumentException("source range line is outside content");
            }
            SourceLine line = line(position.line());
            if (position.character() > line.contentLength()) {
                throw new IllegalArgumentException("source range character is outside content");
            }
            return line.start() + position.character();
        }

        private SyntaxPosition position(int offset) {
            for (int index = 0; index < lines.size(); index++) {
                SourceLine line = line(index);
                if (offset >= line.start() && offset <= line.contentEnd()) {
                    return new SyntaxPosition(index, offset - line.start());
                }
            }
            throw new IllegalArgumentException("source offset is outside content");
        }

        private SourceLine line(int index) {
            return lines.get(index);
        }

        private int lastLineIndex() {
            return lines.size() - 1;
        }
    }
}
