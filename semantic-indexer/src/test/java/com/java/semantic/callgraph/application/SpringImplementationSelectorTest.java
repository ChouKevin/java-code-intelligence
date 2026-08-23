package com.java.semantic.callgraph.application;

import com.java.semantic.callgraph.domain.ResolutionStrategy;
import com.java.semantic.model.codefact.JavaTypeIdentity;
import com.java.semantic.model.codefact.MethodTarget;
import com.java.semantic.model.codefact.SourceTypeIdentity;
import com.java.semantic.semantic.domain.SemanticLocation;
import com.java.semantic.semantic.domain.SemanticMethod;
import com.java.semantic.semantic.domain.SemanticPosition;
import com.java.semantic.semantic.domain.SemanticRange;
import com.java.semantic.syntax.domain.SyntaxInvocation;
import com.java.semantic.model.codefact.SyntaxPosition;
import com.java.semantic.model.codefact.SyntaxRange;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SpringImplementationSelectorTest {

    @Test
    void should_select_a_unique_qualified_candidate() {
        ImplementationCandidate candidate = candidate("FastWorker", true, List.of("fast"));
        ImplementationSelection selection = new SpringImplementationSelector().select(invocation("fast"), List.of(candidate));

        assertThat(selection).isInstanceOf(ImplementationSelection.Selected.class);
        ImplementationSelection.Selected selected = (ImplementationSelection.Selected) selection;
        assertThat(selected.candidate()).isEqualTo(candidate);
        assertThat(selected.strategy()).isEqualTo(ResolutionStrategy.SPRING_BEAN_BY_QUALIFIER);
    }

    @Test
    void should_return_complete_sorted_candidates_when_unqualified_implementations_remain() {
        ImplementationCandidate zeta = candidate("ZetaWorker", false, List.of());
        ImplementationCandidate alpha = candidate("AlphaWorker", false, List.of());
        ImplementationSelection selection = new SpringImplementationSelector().select(invocation(""), List.of(zeta, alpha));

        assertThat(selection).isInstanceOf(ImplementationSelection.Ambiguous.class);
        assertThat(((ImplementationSelection.Ambiguous) selection).candidates())
                .extracting(MethodTarget::className)
                .containsExactly("AlphaWorker", "ZetaWorker");
    }

    @Test
    void should_select_the_only_primary_candidate() {
        ImplementationCandidate primary = candidate("PrimaryWorker", true, List.of());
        ImplementationCandidate fallback = candidate("FallbackWorker", false, List.of());

        ImplementationSelection selection = new SpringImplementationSelector()
                .select(invocation(""), List.of(fallback, primary));

        assertThat(selection).isEqualTo(new ImplementationSelection.Selected(
                primary, ResolutionStrategy.SPRING_BEAN_BY_PRIMARY));
    }

    @Test
    void should_select_a_single_default_candidate() {
        ImplementationCandidate defaultCandidate = candidate("DefaultWorker", false, List.of());

        ImplementationSelection selection = new SpringImplementationSelector()
                .select(invocation(""), List.of(defaultCandidate));

        assertThat(selection).isEqualTo(new ImplementationSelection.Selected(
                defaultCandidate, ResolutionStrategy.SPRING_SINGLE_IMPLEMENTATION));
    }

    @Test
    void should_fail_closed_when_a_qualifier_matches_multiple_overrides() {
        ImplementationCandidate inheritedDefault = candidate("InheritedDefaultWorker", false, List.of("shared"));
        ImplementationCandidate override = candidate("OverrideWorker", false, List.of("shared"));

        ImplementationSelection selection = new SpringImplementationSelector()
                .select(invocation("shared"), List.of(override, inheritedDefault));

        assertThat(selection).isEqualTo(new ImplementationSelection.Ambiguous(List.of(
                inheritedDefault.target(), override.target())));
    }

    private static ImplementationCandidate candidate(String className, boolean primary, List<String> qualifiers) {
        MethodTarget target = new MethodTarget(
                new SourceTypeIdentity(
                        new JavaTypeIdentity("com.example", className),
                        className + ".java"),
                "work",
                List.of());
        SemanticRange range = new SemanticRange(new SemanticPosition(0, 0), new SemanticPosition(1, 0));
        SemanticMethod method = new SemanticMethod(
                "com.example", className, "work", List.of(), "void",
                new SemanticLocation("file:///fixture/" + className + ".java", range, range));
        return new ImplementationCandidate(method, target, primary, qualifiers, List.of());
    }

    private static SyntaxInvocation invocation(String qualifier) {
        SyntaxRange range = new SyntaxRange(new SyntaxPosition(0, 0), new SyntaxPosition(0, 4));
        return new SyntaxInvocation(
                SyntaxInvocation.InvocationKind.METHOD, range, "work()", "worker", "", qualifier,
                Optional.empty(), range.start());
    }
}
