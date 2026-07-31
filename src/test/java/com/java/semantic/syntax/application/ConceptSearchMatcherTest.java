package com.java.semantic.syntax.application;

import com.java.semantic.syntax.application.ConceptIdentity.TypeConceptIdentity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/** 結構化概念搜尋比對規則 */
class ConceptSearchMatcherTest {

    private final ConceptSearchMatcher matcher = new ConceptSearchMatcher();

    @Test
    void should_tokenize_camel_pascal_snake_kebab_dot_and_route_boundaries() {
        Set<String> tokens = ConceptSearchTokenizer.tokenize(
                "OrderService findPendingOrders purchase_order order-items orders.created /api/order-items");

        assertThat(tokens).containsExactlyInAnyOrder(
                "order", "service", "find", "pending", "orders", "purchase", "items", "created", "api");
    }

    @Test
    void should_match_exact_prefix_and_canonical_values_case_insensitively() {
        ConceptCatalogEntry entry = entry("com.acme.order.OrderService#createOrder(java.lang.String)");

        assertThat(matcher.matches(entry, List.of(new ConceptSearchTerm("order", ConceptMatchMode.TOKEN_EXACT))))
                .isTrue();
        assertThat(matcher.matches(entry, List.of(new ConceptSearchTerm("cre", ConceptMatchMode.TOKEN_PREFIX))))
                .isTrue();
        assertThat(matcher.matches(entry, List.of(new ConceptSearchTerm(
                "COM.ACME.ORDER.ORDERSERVICE#CREATEORDER(JAVA.LANG.STRING)",
                ConceptMatchMode.CANONICAL_EXACT)))).isTrue();
    }

    @Test
    void should_require_every_term_to_match_the_same_entry_without_stemming() {
        ConceptCatalogEntry entry = entry("com.acme.order.OrderService#createOrder(java.lang.String)");

        assertThat(matcher.matches(entry, List.of(
                new ConceptSearchTerm("order", ConceptMatchMode.TOKEN_EXACT),
                new ConceptSearchTerm("create", ConceptMatchMode.TOKEN_PREFIX)))).isTrue();
        assertThat(matcher.matches(entry, List.of(new ConceptSearchTerm("orders", ConceptMatchMode.TOKEN_EXACT))))
                .isFalse();
    }

    @Test
    void should_accept_only_trailing_prefix_shorthand_and_reject_control_characters() {
        ConceptSearchTerm shorthand = new ConceptSearchTerm("order*", ConceptMatchMode.TOKEN_EXACT);

        assertThat(shorthand.value()).isEqualTo("order");
        assertThat(shorthand.matchMode()).isEqualTo(ConceptMatchMode.TOKEN_PREFIX);
        assertThatIllegalArgumentException().isThrownBy(() -> new ConceptSearchTerm("*", ConceptMatchMode.TOKEN_PREFIX));
        assertThatIllegalArgumentException().isThrownBy(() -> new ConceptSearchTerm("or*der", ConceptMatchMode.TOKEN_PREFIX));
        assertThatIllegalArgumentException().isThrownBy(() -> new ConceptSearchTerm("order**", ConceptMatchMode.TOKEN_PREFIX));
        assertThatIllegalArgumentException().isThrownBy(() -> new ConceptSearchTerm("order\n", ConceptMatchMode.TOKEN_PREFIX));
        assertThatIllegalArgumentException().isThrownBy(() -> new ConceptSearchTerm("order\u0000", ConceptMatchMode.TOKEN_PREFIX));
    }

    @Test
    void should_exclude_all_and_unresolved_from_search_tokens() {
        Set<String> tokens = ConceptSearchTokenizer.tokenize("ALL <unresolved> OrderService");

        assertThat(tokens).contains("order", "service");
        assertThat(tokens).doesNotContain("all", "unresolved");
    }

    @Test
    void should_preserve_identifier_tokens_that_match_metadata_sentinel_words() {
        Set<String> tokens = ConceptSearchTokenizer.tokenize("ALL <unresolved> AllOrdersController UnresolvedOrder");

        assertThat(tokens).contains("all", "orders", "controller", "unresolved", "order");
        assertThatCode(() -> entry("com.acme.order.AllOrdersController#unresolvedOrder()"))
                .doesNotThrowAnyException();
    }

    private static ConceptCatalogEntry entry(String canonicalValue) {
        TypeConceptIdentity identity = new TypeConceptIdentity(
                "src/main/java/com/acme/order/OrderService.java", "com.acme.order.OrderService");
        return new ConceptCatalogEntry(
                "test-provider",
                identity,
                canonicalValue,
                "OrderService.createOrder",
                ConceptSearchTokenizer.tokenize(canonicalValue),
                "com.acme.order",
                Optional.of("com.acme.order.OrderService"),
                ConceptAuthority.SYNTAX_DECLARED,
                Set.of(identity));
    }
}
