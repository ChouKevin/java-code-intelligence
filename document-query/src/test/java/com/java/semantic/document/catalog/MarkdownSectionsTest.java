package com.java.semantic.document.catalog;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class MarkdownSectionsTest {
    @Test
    void fencesAndDuplicateHeadingsRetainOriginalLfLocations() {
        String content = "前言\r\n# VIP\r\n規則\r\n```java\r\n# not a heading\r\n```\r\n## VIP\r\n末尾";
        List<MarkdownSections.Span> spans = MarkdownSections.parse(content);
        assertThat(spans).extracting(MarkdownSections.Span::startLine).containsExactly(1, 2, 7);
        assertThat(spans).extracting(MarkdownSections.Span::title).containsExactly("", "VIP", "VIP");
        assertThat(content.substring(spans.get(1).startOffset(), spans.get(1).endOffset()))
                .isEqualTo("# VIP\r\n規則\r\n```java\r\n# not a heading\r\n```\r\n");
        assertThat(spans.get(2).endLine()).isEqualTo(8);
    }

    @Test
    void tildeFenceAndIndentedCodeDoNotInventSections() {
        String content = "# Start\n~~~\n## hidden\n~~~~\n    # indented code\n   ## Shown ###\ntext\n";
        List<MarkdownSections.Span> spans = MarkdownSections.parse(content);
        assertThat(spans).extracting(MarkdownSections.Span::startLine).containsExactly(1, 6);
        assertThat(spans).extracting(MarkdownSections.Span::title).containsExactly("Start", "Shown");
        assertThat(spans.get(1).endLine()).isEqualTo(7);
    }

    @Test
    void backtickInfoContainingBacktickDoesNotStartFence() {
        String content = "```bad`info\n# visible\n";
        assertThat(MarkdownSections.parse(content)).extracting(MarkdownSections.Span::startLine).containsExactly(1, 2);
    }
}
