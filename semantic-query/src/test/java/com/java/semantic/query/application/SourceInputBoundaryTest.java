package com.java.semantic.query.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.java.semantic.model.source.SourceReadContract.ReadSourceRequest;
import com.java.semantic.model.source.SourceReadContract.LineRange;
import java.util.Collections;
import java.util.Optional;
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
        assertThat(request.startLine()).contains(5);
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

    @Test
    void binds_selectors_before_defaults_and_context_depends_on_view_and_range_count() {
        ReadSourceRequest single = SemanticQueryInput.readSource(Map.of("context", CONTEXT, "path", "A.java",
                "ranges", List.of(Map.of("startLine", 5, "endLine", 8))));
        assertThat(single.startLine()).isEmpty();
        assertThat(single.contextBefore()).isEqualTo(1);
        assertThat(single.contextAfter()).isEqualTo(3);
        ReadSourceRequest multiple = SemanticQueryInput.readSource(Map.of("context", CONTEXT, "path", "A.java",
                "ranges", List.of(Map.of("startLine", 5, "endLine", 8), Map.of("startLine", 20, "endLine", 21))));
        assertThat(multiple.contextBefore()).isZero();
        assertThat(multiple.contextAfter()).isZero();
        ReadSourceRequest raw = SemanticQueryInput.readSource(Map.of("context", CONTEXT, "path", "A.java", "view", "raw",
                "ranges", List.of(Map.of("startLine", 5, "endLine", 8))));
        assertThat(raw.contextBefore()).isZero();
        assertThat(raw.contextAfter()).isZero();
        assertThat(SemanticQueryInput.listFiles(Map.of("context", CONTEXT, "depth", 2)).depth()).isEqualTo(2);
    }

    @Test
    void twenty_ranges_are_accepted_but_twenty_one_are_rejected_at_both_boundaries() {
        List<Map<String, Integer>> twenty = Collections.nCopies(20, Map.of("startLine", 1, "endLine", 2));
        ReadSourceRequest accepted = SemanticQueryInput.readSource(Map.of("context", CONTEXT,
                "path", "A.java", "ranges", twenty));
        assertThat(accepted.ranges()).containsExactlyElementsOf(Collections.nCopies(20, new LineRange(1, 2)));
        assertThatThrownBy(() -> SemanticQueryInput.readSource(Map.of("context", CONTEXT,
                "path", "A.java", "ranges", Collections.nCopies(21, twenty.getFirst()))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReadSourceRequest(accepted.context(), accepted.path(), accepted.view(),
                Optional.empty(), Collections.nCopies(21, new LineRange(1, 2)), Optional.empty(), 200, 0, 0,
                Optional.empty(), Optional.empty())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_ambiguous_selectors_summary_selectors_noncanonical_views_and_raw_context() {
        List<Map<String, Object>> invalid = List.of(
                Map.of("startLine", 1, "tailLines", 1),
                Map.of("startLine", 1, "ranges", List.of(Map.of("startLine", 1, "endLine", 2))),
                Map.of("view", "summary", "startLine", 1),
                Map.of("view", "RAW"),
                Map.of("ranges", List.of()),
                Map.of("tailLines", 501),
                Map.of("view", "raw", "ranges", List.of(Map.of("startLine", 1, "endLine", 2)), "contextBefore", 1),
                Map.of("startLine", 1, "contextAfter", 1),
                Map.of("ranges", List.of(Map.of("startLine", 2, "endLine", 1))),
                Map.of("indexGeneration", "00000000-0000-0000-0000-000000000001"));
        for (Map<String, Object> fields : invalid) {
            Map<String, Object> input = new HashMap<>(fields);
            input.put("context", CONTEXT);
            input.put("path", "A.java");
            assertThatThrownBy(() -> SemanticQueryInput.readSource(input)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> SemanticQueryInput.listFiles(Map.of("context", CONTEXT, "depth", 3)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
