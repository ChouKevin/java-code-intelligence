package com.java.semantic.callgraph.application;

import com.java.semantic.callgraph.domain.ResolutionStrategy;
import com.java.semantic.model.codefact.MethodTarget;
import com.java.semantic.syntax.domain.SyntaxInvocation;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Selects a Spring implementation only from syntax-proven qualifier and primary metadata. */
public final class SpringImplementationSelector {

    private static final Comparator<ImplementationCandidate> CANONICAL_ORDER = Comparator
            .comparing((ImplementationCandidate candidate) -> candidate.target().sourceFile())
            .thenComparing(candidate -> candidate.target().packageName())
            .thenComparing(candidate -> candidate.target().className())
            .thenComparing(candidate -> candidate.target().methodName())
            .thenComparing(candidate -> candidate.target().parameterTypes(), SpringImplementationSelector::compareParameters);

    public ImplementationSelection select(
            SyntaxInvocation invocation, List<ImplementationCandidate> candidates) {
        Objects.requireNonNull(invocation, "invocation is required");
        List<ImplementationCandidate> ordered = canonicalOrder(candidates);
        Assert.notEmpty(ordered, "candidates must not be empty");
        if (StringUtils.hasText(invocation.qualifier())) {
            List<ImplementationCandidate> qualifierMatches = ordered.stream()
                    .filter(candidate -> candidate.qualifiers().contains(invocation.qualifier()))
                    .toList();
            if (qualifierMatches.size() == 1) {
                return selected(qualifierMatches.getFirst(), ResolutionStrategy.SPRING_BEAN_BY_QUALIFIER);
            }
            return ambiguous(qualifierMatches.isEmpty() ? ordered : qualifierMatches);
        }
        List<ImplementationCandidate> primary = ordered.stream().filter(ImplementationCandidate::primary).toList();
        if (primary.size() == 1) {
            return selected(primary.getFirst(), ResolutionStrategy.SPRING_BEAN_BY_PRIMARY);
        }
        if (ordered.size() == 1) {
            return selected(ordered.getFirst(), ResolutionStrategy.SPRING_SINGLE_IMPLEMENTATION);
        }
        return ambiguous(ordered);
    }

    private ImplementationSelection.Selected selected(
            ImplementationCandidate candidate, ResolutionStrategy strategy) {
        return new ImplementationSelection.Selected(candidate, strategy);
    }

    private ImplementationSelection.Ambiguous ambiguous(List<ImplementationCandidate> candidates) {
        List<MethodTarget> targets = candidates.stream().map(ImplementationCandidate::target).toList();
        return new ImplementationSelection.Ambiguous(targets);
    }

    private List<ImplementationCandidate> canonicalOrder(List<ImplementationCandidate> candidates) {
        return Objects.requireNonNull(candidates, "candidates are required").stream()
                .distinct()
                .sorted(CANONICAL_ORDER)
                .toList();
    }

    private static int compareParameters(List<String> left, List<String> right) {
        int shared = Math.min(left.size(), right.size());
        for (int index = 0; index < shared; index++) {
            int comparison = left.get(index).compareTo(right.get(index));
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(left.size(), right.size());
    }
}
