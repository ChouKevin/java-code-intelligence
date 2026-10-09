package com.java.semantic.model.source;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RepositoryNavigationHintTest {
    @Test
    void preserves_unicode_and_ordinal_keyword_order_deduplicating_before_the_limit() {
        List<String> keywords = new ArrayList<>();
        for (int index = 0; index < 12; index++) keywords.add("規則" + index);
        keywords.add("規則0");
        RepositoryNavigationHint hint = hint("🙂".repeat(240), keywords);
        keywords.clear();
        assertEquals(12, hint.keywords().size());
        assertEquals("規則0", hint.keywords().getFirst());
        assertEquals("規則11", hint.keywords().getLast());
        assertEquals("🙂".repeat(240), hint.summary());
        assertThrows(UnsupportedOperationException.class, () -> hint.keywords().add("new"));
        assertEquals(List.of("VIP", "vip"), hint("rules", List.of("VIP", "vip", "VIP")).keywords());
    }

    @Test
    void rejects_invalid_unicode_multiline_controls_and_content_limits_without_truncation() {
        for (String invalid : List.of("", " ", "x".repeat(241), "\uD800", "\uDC00", "a\uD800b",
                "a\nb", "a\rb", "a\tb", "a\u0000b", "a\u007fb", "a\u0085b", "a\u2028b", "a\u2029b")) {
            assertThrows(IllegalArgumentException.class, () -> hint(invalid, List.of("rules")));
        }
        for (String invalid : List.of("", "x".repeat(33), "\uD800", "a\u2028b", "a\u2029b", "a\tb")) {
            assertThrows(IllegalArgumentException.class, () -> hint("rules", List.of(invalid)));
        }
        assertEquals("🙂".repeat(32), hint("rules", List.of("🙂".repeat(32))).keywords().getFirst());
        assertThrows(IllegalArgumentException.class, () -> hint("rules", List.of()));
        assertThrows(IllegalArgumentException.class, () -> hint("rules",
                java.util.stream.IntStream.range(0, 13).mapToObj(Integer::toString).toList()));
        for (String revision : List.of("main", "A".repeat(40), "a".repeat(39))) {
            assertThrows(IllegalArgumentException.class, () -> new RepositoryNavigationHint("rules", List.of("VIP"),
                    revision, RepositoryNavigationHint.ReviewStatus.APPROVED, RepositoryNavigationHint.Trust.NOT_VERIFIED));
        }
    }

    private static RepositoryNavigationHint hint(String summary, List<String> keywords) {
        return new RepositoryNavigationHint(summary, keywords, "a".repeat(40),
                RepositoryNavigationHint.ReviewStatus.APPROVED, RepositoryNavigationHint.Trust.NOT_VERIFIED);
    }
}
