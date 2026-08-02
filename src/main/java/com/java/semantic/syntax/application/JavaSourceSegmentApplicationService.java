package com.java.semantic.syntax.application;

import com.java.semantic.repository.application.RepositoryApplicationService;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.repository.domain.RepositorySourceContainment;
import com.java.semantic.repository.domain.RepositorySourceContainmentResult;
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

/**
 * 在固定 revision 讀取受 64 KiB 限制的 Java 原始碼範圍
 * 此服務是 Agent 收到 discovery 結果後取得有限上下文的續讀路徑
 */
public final class JavaSourceSegmentApplicationService {

    private static final int MAX_UTF8_BYTES = 64 * 1024;

    private final RepositoryApplicationService repositoryApplicationService;
    private final RepositorySourceContainment containment;

    public JavaSourceSegmentApplicationService(
            RepositoryApplicationService repositoryApplicationService,
            RepositorySourceContainment containment) {
        this.repositoryApplicationService = Objects.requireNonNull(
                repositoryApplicationService, "repositoryApplicationService is required");
        this.containment = Objects.requireNonNull(containment, "containment is required");
    }

    /** 在 expected revision 的讀鎖內完成安全檔案讀取與 bounded materialization */
    public JavaSourceSegmentResult read(JavaSourceSegmentQuery query) {
        JavaSourceSegmentQuery requiredQuery = Objects.requireNonNull(query, "query is required");
        return repositoryApplicationService.withSnapshot(
                requiredQuery.repositoryId(),
                Optional.of(requiredQuery.expectedRevision()),
                snapshot -> readSnapshot(snapshot, requiredQuery));
    }

    private JavaSourceSegmentResult readSnapshot(RepositorySnapshot snapshot, JavaSourceSegmentQuery query) {
        Path candidate = snapshot.root().resolve(query.sourceRange().sourceFile());
        RepositorySourceContainmentResult classification = containment.classify(snapshot.root(), candidate);
        if (!(classification instanceof RepositorySourceContainmentResult.ContainedSource contained)) {
            throw new SourceSegmentNotFoundException();
        }
        if (!contained.sourceFile().equals(query.sourceRange().sourceFile())) {
            throw new SourceSegmentNotFoundException();
        }
        String content = readSource(contained.realPath());
        SourceText source = SourceText.from(content);
        SyntaxRange requested = query.sourceRange().range();
        int exactStart = source.offset(requested.start());
        int exactEnd = source.offset(requested.end());
        if (exactEnd < exactStart) {
            throw new IllegalArgumentException("source range end must not precede start");
        }
        if (utf8Bytes(content.substring(exactStart, exactEnd)) > MAX_UTF8_BYTES) {
            throw new SourceSegmentTooLargeException();
        }

        int requestedBefore = query.contextLines();
        int requestedAfter = query.contextLines();
        int before = Math.min(requestedBefore, requested.start().line());
        int after = Math.min(requestedAfter, source.lastLineIndex() - requested.end().line());
        Segment segment = segment(source, content, requested, exactStart, exactEnd, before, after);
        while (segment.utf8Bytes() > MAX_UTF8_BYTES) {
            if (after > 0 && after >= before) {
                after--;
            } else if (before > 0) {
                before--;
            } else {
                throw new SourceSegmentTooLargeException();
            }
            segment = segment(source, content, requested, exactStart, exactEnd, before, after);
        }

        boolean truncated = before < requestedBefore || after < requestedAfter;
        return new JavaSourceSegmentResult(
                snapshot.repositoryId(),
                snapshot.revision(),
                query.sourceRange(),
                new SourceRange(query.sourceRange().sourceFile(), segment.range()),
                segment.content(),
                truncated,
                segment.utf8Bytes());
    }

    private Segment segment(
            SourceText source,
            String content,
            SyntaxRange requested,
            int exactStart,
            int exactEnd,
            int before,
            int after) {
        int startLine = requested.start().line() - before;
        int endLine = requested.end().line() + after;
        int start = before > 0 ? source.line(startLine).start() : exactStart;
        int end = after > 0 ? source.line(endLine).contentEnd() : exactEnd;
        SyntaxPosition rangeStart = before > 0
                ? new SyntaxPosition(startLine, 0)
                : requested.start();
        SyntaxPosition rangeEnd = after > 0
                ? new SyntaxPosition(endLine, source.line(endLine).contentLength())
                : requested.end();
        String selected = content.substring(start, end);
        return new Segment(new SyntaxRange(rangeStart, rangeEnd), selected, utf8Bytes(selected));
    }

    private String readSource(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            if (!Files.exists(path)) {
                throw new SourceSegmentNotFoundException();
            }
            throw new UncheckedIOException("Failed to read Java source segment", exception);
        }
    }

    private int utf8Bytes(String content) {
        return content.getBytes(StandardCharsets.UTF_8).length;
    }

    private record Segment(SyntaxRange range, String content, int utf8Bytes) {
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

        private SourceLine line(int index) {
            return lines.get(index);
        }

        private int lastLineIndex() {
            return lines.size() - 1;
        }
    }
}
