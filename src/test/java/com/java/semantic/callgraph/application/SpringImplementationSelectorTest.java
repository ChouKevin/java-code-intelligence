package com.java.semantic.callgraph.application;

import com.java.semantic.callgraph.domain.ResolutionStrategy;
import com.java.semantic.semantic.domain.SemanticLocation;
import com.java.semantic.semantic.domain.SemanticMethod;
import com.java.semantic.semantic.domain.SemanticPosition;
import com.java.semantic.semantic.domain.SemanticRange;
import com.java.semantic.syntax.domain.SyntaxInvocation;
import com.java.semantic.syntax.domain.SyntaxInvocation.InvocationKind;
import com.java.semantic.syntax.domain.SyntaxPosition;
import com.java.semantic.syntax.domain.SyntaxRange;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class SpringImplementationSelectorTest {

    private final SpringImplementationSelector selector = new SpringImplementationSelector();

    @Test
    void should_select_qualifier_even_when_receiver_variable_names_another_candidate() {
        ImplementationCandidate slowCandidate = candidate("SlowOrderService", false, List.of(), List.of());
        ImplementationCandidate fastQualifiedCandidate = candidate(
                "FastOrderService", false, List.of("fastOrderService"), List.of());

        ImplementationSelection selection = selector.select(
                invocation("slowService", "fastOrderService"),
                List.of(slowCandidate, fastQualifiedCandidate));

        assertThat(selection.candidates()).containsExactly(fastQualifiedCandidate.method());
        assertThat(selection.strategy()).isEqualTo(ResolutionStrategy.SPRING_BEAN_BY_QUALIFIER);
    }

    @Test
    void should_select_unique_primary() {
        ImplementationCandidate ordinary = candidate("OrdinaryOrderService", false, List.of(), List.of());
        ImplementationCandidate primary = candidate("PrimaryOrderService", true, List.of(), List.of());

        ImplementationSelection selection = selector.select(invocation("orderService", ""), List.of(ordinary, primary));

        assertThat(selection.candidates()).containsExactly(primary.method());
        assertThat(selection.strategy()).isEqualTo(ResolutionStrategy.SPRING_BEAN_BY_PRIMARY);
    }

    @Test
    void should_not_fall_back_to_primary_when_explicit_qualifier_has_no_match() {
        ImplementationCandidate ordinary = candidate("OrdinaryOrderService", false, List.of(), List.of());
        ImplementationCandidate primary = candidate("PrimaryOrderService", true, List.of(), List.of());

        ImplementationSelection selection = selector.select(
                invocation("orderService", "missingOrderService"), List.of(ordinary, primary));

        assertThat(selection.candidates()).containsExactly(ordinary.method(), primary.method());
        assertThat(selection.strategy()).isEqualTo(ResolutionStrategy.SPRING_MULTIPLE_CANDIDATES);
        assertThat(selection.warnings()).singleElement().asString().contains("qualifier");
    }

    @Test
    void should_select_exactly_one_implementation() {
        ImplementationCandidate only = candidate("OnlyOrderService", false, List.of(), List.of());

        ImplementationSelection selection = selector.select(invocation("orderService", ""), List.of(only));

        assertThat(selection.candidates()).containsExactly(only.method());
        assertThat(selection.strategy()).isEqualTo(ResolutionStrategy.SPRING_SINGLE_IMPLEMENTATION);
    }

    @Test
    void should_retain_multiple_primaries_in_canonical_order() {
        ImplementationCandidate zeta = candidate("ZetaOrderService", true, List.of(), List.of());
        ImplementationCandidate alpha = candidate("AlphaOrderService", true, List.of(), List.of());

        ImplementationSelection selection = selector.select(invocation("orderService", ""), List.of(zeta, alpha));

        assertThat(selection.candidates()).containsExactly(alpha.method(), zeta.method());
        assertThat(selection.strategy()).isEqualTo(ResolutionStrategy.SPRING_MULTIPLE_CANDIDATES);
        assertThat(selection.warnings()).singleElement().asString().contains("multiple implementations");
    }

    @Test
    void should_retain_all_profile_candidates_when_no_active_profile_is_known() {
        ImplementationCandidate prodCandidate = candidate("ProdOrderService", false, List.of(), List.of("prod"));
        ImplementationCandidate testCandidate = candidate("TestOrderService", false, List.of(), List.of("test"));

        ImplementationSelection selection = selector.select(
                invocation("orderService", ""), List.of(prodCandidate, testCandidate));

        assertThat(selection.candidates()).containsExactly(prodCandidate.method(), testCandidate.method());
        assertThat(selection.strategy()).isEqualTo(ResolutionStrategy.SPRING_MULTIPLE_CANDIDATES);
        assertThat(selection.warnings()).singleElement().asString().contains("profiles are evidence only");
    }

    @Test
    void should_reject_empty_candidate_input() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> selector.select(invocation("orderService", ""), List.of()))
                .withMessageContaining("candidates");
    }

    private ImplementationCandidate candidate(
            String className, boolean primary, List<String> qualifiers, List<String> profiles) {
        SemanticPosition position = new SemanticPosition(0, 0);
        SemanticRange range = new SemanticRange(position, position);
        SemanticMethod method = new SemanticMethod(
                "com.example", className, "process", List.of("Order"), "Result",
                new SemanticLocation("file:///repo/" + className + ".java", range, range));
        return new ImplementationCandidate(method, primary, qualifiers, profiles);
    }

    private SyntaxInvocation invocation(String receiver, String qualifier) {
        SyntaxPosition position = new SyntaxPosition(0, 0);
        return new SyntaxInvocation(
                InvocationKind.METHOD,
                new SyntaxRange(position, position),
                receiver + ".process(order)",
                receiver,
                "OrderService " + receiver,
                qualifier,
                Optional.empty(),
                position);
    }
}
