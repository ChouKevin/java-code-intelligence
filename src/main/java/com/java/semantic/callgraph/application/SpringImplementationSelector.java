package com.java.semantic.callgraph.application;

import com.java.semantic.callgraph.domain.ResolutionStrategy;
import com.java.semantic.semantic.domain.SemanticMethod;
import com.java.semantic.syntax.domain.SyntaxInvocation;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Selects a Spring implementation only from qualifier and primary metadata. */
public final class SpringImplementationSelector {

    private static final String MULTIPLE_WARNING =
            "multiple implementations; profiles are evidence only";
    private static final String QUALIFIER_WARNING =
            "qualifier did not identify exactly one implementation; profiles are evidence only";

    private static final Comparator<ImplementationCandidate> CANONICAL_ORDER = Comparator
            .comparing((ImplementationCandidate candidate) -> candidate.method().packageName())
            .thenComparing(candidate -> candidate.method().className())
            .thenComparing(candidate -> candidate.method().methodName())
            .thenComparing(candidate -> String.join(",", candidate.method().parameterTypes()))
            .thenComparing(candidate -> candidate.method().location().uri())
            .thenComparingInt(candidate -> candidate.method().location().range().start().line())
            .thenComparingInt(candidate -> candidate.method().location().range().start().character())
            .thenComparingInt(candidate -> candidate.method().location().range().end().line())
            .thenComparingInt(candidate -> candidate.method().location().range().end().character());

    public ImplementationSelection select(
            SyntaxInvocation invocation, List<ImplementationCandidate> candidates) {
        Objects.requireNonNull(invocation, "invocation is required");
        Assert.notEmpty(candidates, "candidates must not be empty");
        List<ImplementationCandidate> ordered = canonicalOrder(candidates);
        if (StringUtils.hasText(invocation.qualifier())) {
            List<ImplementationCandidate> qualifierMatches = ordered.stream()
                    .filter(candidate -> candidate.qualifiers().contains(invocation.qualifier()))
                    .toList();
            if (qualifierMatches.size() == 1) {
                return selected(qualifierMatches, ResolutionStrategy.SPRING_BEAN_BY_QUALIFIER);
            }
            if (qualifierMatches.size() > 1) {
                return ambiguous(qualifierMatches, QUALIFIER_WARNING);
            }
            return ambiguous(ordered, QUALIFIER_WARNING);
        }

        List<ImplementationCandidate> primary = ordered.stream()
                .filter(ImplementationCandidate::primary)
                .toList();
        if (primary.size() == 1) {
            return selected(primary, ResolutionStrategy.SPRING_BEAN_BY_PRIMARY);
        }
        if (ordered.size() == 1) {
            return selected(ordered, ResolutionStrategy.SPRING_SINGLE_IMPLEMENTATION);
        }
        return ambiguous(ordered, MULTIPLE_WARNING);
    }

    private List<ImplementationCandidate> canonicalOrder(List<ImplementationCandidate> candidates) {
        return Objects.requireNonNull(candidates, "candidates are required").stream()
                .sorted(CANONICAL_ORDER)
                .toList();
    }

    private ImplementationSelection selected(
            List<ImplementationCandidate> candidates, ResolutionStrategy strategy) {
        return new ImplementationSelection(methods(candidates), strategy, List.of());
    }

    private ImplementationSelection ambiguous(List<ImplementationCandidate> candidates, String warning) {
        return new ImplementationSelection(
                methods(candidates), ResolutionStrategy.SPRING_MULTIPLE_CANDIDATES, List.of(warning));
    }

    private List<SemanticMethod> methods(List<ImplementationCandidate> candidates) {
        return candidates.stream().map(ImplementationCandidate::method).toList();
    }
}
