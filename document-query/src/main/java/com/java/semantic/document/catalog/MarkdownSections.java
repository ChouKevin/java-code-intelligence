package com.java.semantic.document.catalog;

import com.java.semantic.document.application.DocumentContract;
import com.java.semantic.document.application.DocumentFailure;
import com.java.semantic.document.application.DocumentDeadline;
import java.util.ArrayList;
import java.util.List;

public final class MarkdownSections {
    private MarkdownSections() { }
    public record Span(int startLine, int endLine, int startOffset, int endOffset, String title) {
        public DocumentContract.Section section() {
            DocumentContract.Clipped clipped = DocumentContract.clip(title, DocumentContract.MAX_TITLE_CODE_POINTS);
            return new DocumentContract.Section(startLine, clipped.text(), clipped.truncated());
        }
    }
    private record Heading(int line, int offset, String title) { }

    public static List<Span> parse(String content) {
        return parse(content, DocumentContract.MAX_SECTIONS, DocumentDeadline.after(DocumentContract.OPERATION_TIMEOUT));
    }

    static List<Span> parse(String content, int remainingSections, DocumentDeadline deadline) {
        int maximumSections = Math.min(DocumentContract.MAX_SECTIONS, remainingSections);
        deadline.check();
        if (maximumSections < 1) { throw new DocumentFailure(DocumentFailure.Code.DOCUMENT_CAPACITY_EXCEEDED); }
        List<Heading> headings = new ArrayList<>();
        int offset = 0;
        int line = 1;
        int lastLine = 1;
        char fence = 0;
        int fenceLength = 0;
        while (offset < content.length()) {
            deadline.check();
            int newline = content.indexOf('\n', offset);
            int end = newline < 0 ? content.length() : newline;
            int visibleEnd = end > offset && content.charAt(end - 1) == '\r' ? end - 1 : end;
            int first = offset;
            while (first < visibleEnd && content.charAt(first) == ' ') { first++; }
            if (first - offset <= 3 && first < visibleEnd) {
                char marker = content.charAt(first);
                int runEnd = first;
                while (runEnd < visibleEnd && content.charAt(runEnd) == marker) { runEnd++; }
                int run = runEnd - first;
                if (fence != 0) {
                    if (marker == fence && run >= fenceLength && horizontalWhitespace(content, runEnd, visibleEnd)) {
                        fence = 0;
                    }
                } else if ((marker == '`' || marker == '~') && run >= 3
                        && (marker != '`' || content.indexOf('`', runEnd) < 0 || content.indexOf('`', runEnd) >= visibleEnd)) {
                    fence = marker;
                    fenceLength = run;
                } else if (marker == '#' && run <= 6
                        && (runEnd == visibleEnd || content.charAt(runEnd) == ' ' || content.charAt(runEnd) == '\t')) {
                    int addedSections = headings.isEmpty() && line > 1 ? 2 : 1;
                    if (addedSections > maximumSections - headings.size()) {
                        throw new DocumentFailure(DocumentFailure.Code.DOCUMENT_CAPACITY_EXCEEDED);
                    }
                    int titleStart = runEnd;
                    while (titleStart < visibleEnd && (content.charAt(titleStart) == ' ' || content.charAt(titleStart) == '\t')) { titleStart++; }
                    int titleEnd = visibleEnd;
                    while (titleEnd > titleStart && (content.charAt(titleEnd - 1) == ' ' || content.charAt(titleEnd - 1) == '\t')) { titleEnd--; }
                    int closing = titleEnd;
                    while (closing > titleStart && content.charAt(closing - 1) == '#') { closing--; }
                    if (closing < titleEnd && (closing == titleStart || content.charAt(closing - 1) == ' ' || content.charAt(closing - 1) == '\t')) {
                        titleEnd = closing;
                        while (titleEnd > titleStart && (content.charAt(titleEnd - 1) == ' ' || content.charAt(titleEnd - 1) == '\t')) { titleEnd--; }
                    }
                    if (headings.isEmpty() && line > 1) { headings.add(new Heading(1, 0, "")); }
                    headings.add(new Heading(line, offset, content.substring(titleStart, titleEnd)));
                }
            }
            lastLine = line;
            offset = newline < 0 ? content.length() : newline + 1;
            line++;
        }
        if (headings.isEmpty()) { headings.add(new Heading(1, 0, "")); }
        List<Span> spans = new ArrayList<>(headings.size());
        for (int index = 0; index < headings.size(); index++) {
            deadline.check();
            Heading heading = headings.get(index);
            boolean last = index + 1 == headings.size();
            spans.add(new Span(heading.line(), last ? lastLine : headings.get(index + 1).line() - 1,
                    heading.offset(), last ? content.length() : headings.get(index + 1).offset(), heading.title()));
        }
        return List.copyOf(spans);
    }

    private static boolean horizontalWhitespace(String text, int start, int end) {
        for (int index = start; index < end; index++) {
            if (text.charAt(index) != ' ' && text.charAt(index) != '\t') { return false; }
        }
        return true;
    }
}
