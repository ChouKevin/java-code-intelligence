package com.java.semantic.query.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.java.semantic.model.source.SourceReadContract.ReadSourceRequest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SourceInputBoundaryTest {

    private static final Map<String, Object> CONTEXT = Map.of("repositoryId", "orders", "revision", "a".repeat(40));

    @Test
    void bindsAnExactSourceWindowWithoutAcceptingAnObsoleteTargetUnion() {
        ReadSourceRequest request = SemanticQueryInput.readSource(Map.of("context", CONTEXT,
                "path", "src/付款 flow😀.java", "startLine", 5, "maxLines", 7));
        assertThat(request.context().repositoryId()).isEqualTo("orders");
        assertThat(request.context().revision()).isEqualTo("a".repeat(40));
        assertThat(request.path()).isEqualTo("src/付款 flow😀.java");
        assertThat(request.startLine()).isEqualTo(5);
        assertThat(request.maxLines()).isEqualTo(7);
        assertThatThrownBy(() -> SemanticQueryInput.readSource(Map.of("context", CONTEXT,
                "target", Map.of("kind", "FILE", "path", "src/A.java"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsLegacyAndUnknownIdentityFieldsInsteadOfSilentlySelectingCurrent() {
        for (String field : List.of("kind", "generationId", "reviewId", "side", "branch", "snapshotId")) {
            Map<String, Object> wrongContext = new HashMap<>(CONTEXT);
            wrongContext.put(field, "legacy");
            assertThatThrownBy(() -> SemanticQueryInput.listFiles(Map.of("context", wrongContext)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> SemanticQueryInput.getContext(Map.of("repositoryId", "orders",
                "selector", Map.of("kind", "CURRENT")))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SemanticQueryInput.getContext(Map.of("repositoryId", "orders", "revision", "main")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsCoercedFractionalOverflowAndOutOfRangePageNumbers() {
        for (Object limit : List.of("20", 1.5, true, Long.MAX_VALUE, 0, 101)) {
            assertThatThrownBy(() -> SemanticQueryInput.searchText(Map.of("context", CONTEXT,
                    "query", "literal", "limit", limit))).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> SemanticQueryInput.readSource(Map.of("context", CONTEXT,
                "path", "src/A.java", "startLine", 0))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SemanticQueryInput.readSource(Map.of("context", CONTEXT,
                "path", "src/A.java", "maxLines", 501))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void treatsMissingOptionalValuesDifferentlyFromExplicitNullOrMalformedUnicode() {
        Map<String, Object> explicitNull = new HashMap<>(Map.of("repositoryId", "orders"));
        explicitNull.put("revision", null);
        assertThat(SemanticQueryInput.getContext(Map.of("repositoryId", "orders")).revision()).isEmpty();
        assertThatThrownBy(() -> SemanticQueryInput.getContext(explicitNull)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SemanticQueryInput.searchText(Map.of("context", CONTEXT, "query", "\uD800")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void countsUnicodeCodePointsAndRejectsMultilineOrNegatedGlobSearches() {
        assertThat(SemanticQueryInput.searchText(Map.of("context", CONTEXT, "query", "😀".repeat(256))).query())
                .isEqualTo("😀".repeat(256));
        for (String invalidQuery : List.of("😀".repeat(257), "first\nsecond", "first\rsecond", "a\u0000b")) {
            assertThatThrownBy(() -> SemanticQueryInput.searchText(Map.of("context", CONTEXT, "query", invalidQuery)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> SemanticQueryInput.searchText(Map.of("context", CONTEXT,
                "query", "literal", "filePattern", "!**/A.java"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SemanticQueryInput.searchText(Map.of("context", CONTEXT,
                "query", "literal", "cursor", "old-search-cursor"))).isInstanceOf(IllegalArgumentException.class);
    }
}
