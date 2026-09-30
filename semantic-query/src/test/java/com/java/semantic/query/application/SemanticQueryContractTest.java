package com.java.semantic.query.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SemanticQueryContractTest {
    private static final Map<String, Object> CURRENT = Map.of("kind", "CURRENT", "repositoryId", "orders", "revision", "a".repeat(40));

    @Test
    void read_context_never_accepts_review_identity_on_current_or_missing_review_membership() {
        assertThatThrownBy(() -> SemanticQueryInput.readContext(Map.of("kind", "CURRENT", "repositoryId", "orders",
                "revision", "a".repeat(40), "reviewId", "review-1"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SemanticQueryInput.readContext(Map.of("kind", "REVIEW", "repositoryId", "orders",
                "revision", "a".repeat(40), "reviewId", "review-1"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void source_target_union_rejects_file_fields_on_fact_and_context_lines_outside_bounds() {
        assertThatThrownBy(() -> SemanticQueryInput.readSource(Map.of("context", CURRENT, "target",
                Map.of("kind", "FACT", "factId", "b".repeat(64), "path", "src/Order.java")))).isInstanceOf(IllegalArgumentException.class);
        for (int contextLines : List.of(-1, 21)) {
            assertThatThrownBy(() -> SemanticQueryInput.readSource(Map.of("context", CURRENT, "target",
                    Map.of("kind", "FACT", "factId", "b".repeat(64), "contextLines", contextLines)))).isInstanceOf(IllegalArgumentException.class);
        }
        SemanticQueryContract.SourceRequest request = SemanticQueryInput.readSource(Map.of("context", CURRENT,
                "target", Map.of("kind", "FACT", "factId", "b".repeat(64), "contextLines", 20)));
        assertThat(request.target().contextLines()).contains(20);
        assertThat(request.target().startLine()).isEmpty();
    }

    @Test
    void legal_transport_numbers_are_exact_and_unknown_nested_fields_are_rejected() {
        for (Object limit : List.of(0, 101, 1.5, "20")) {
            assertThatThrownBy(() -> SemanticQueryInput.searchCode(Map.of("context", CURRENT, "query", "Order", "limit", limit)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(SemanticQueryInput.searchCode(Map.of("context", CURRENT, "query", "Order", "limit", 100)).page().limit()).isEqualTo(100);
        assertThatThrownBy(() -> SemanticQueryInput.getContext(Map.of("repositoryId", "orders", "selector",
                Map.of("kind", "CURRENT", "revision", "a".repeat(40))))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void kind_specific_entry_filters_require_matching_kind_and_duplicate_kinds_are_invalid() {
        assertThatThrownBy(() -> SemanticQueryInput.entryPoints(Map.of("context", CURRENT, "eventType", "example.Event")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SemanticQueryInput.entryPoints(Map.of("context", CURRENT, "kind", "HTTP", "destination",
                Map.of("broker", "kafka", "destination", "orders")))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SemanticQueryInput.searchCode(Map.of("context", CURRENT, "query", "Order", "kinds", List.of("TYPE", "TYPE"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void empty_before_is_explicit_and_never_a_fake_revision() {
        SemanticQueryContract.ComparisonContext comparison = SemanticQueryInput.comparisonContext(Map.of("repositoryId", "orders",
                "reviewId", "review-1", "before", Map.of("kind", "EMPTY_TREE"), "after", Map.of("kind", "REVISION", "revision", "a".repeat(40))));
        assertThat(comparison.before().revision()).isEmpty();
        assertThatThrownBy(() -> SemanticQueryInput.comparisonContext(Map.of("repositoryId", "orders", "reviewId", "review-1",
                "before", Map.of("kind", "EMPTY_TREE", "revision", "0".repeat(40)), "after", Map.of("kind", "REVISION", "revision", "a".repeat(40)))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
