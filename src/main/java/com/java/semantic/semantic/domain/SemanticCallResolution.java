package com.java.semantic.semantic.domain;

import org.springframework.util.Assert;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Immutable, fail-closed result of a descendant definition lookup. */
public record SemanticCallResolution(
        SemanticCallResolutionStatus status,
        Optional<SemanticCall> call,
        List<SemanticMethod> candidates) {

    public SemanticCallResolution {
        status = Objects.requireNonNull(status, "status is required");
        call = Objects.requireNonNull(call, "call is required");
        candidates = normalizedCandidates(candidates);
        switch (status) {
            case RESOLVED -> {
                Assert.isTrue(call.isPresent(), "resolved call is required");
                Assert.isTrue(candidates.isEmpty(), "resolved candidates must be empty");
            }
            case UNRESOLVED -> {
                Assert.isTrue(call.isEmpty(), "unresolved call must be empty");
                Assert.isTrue(candidates.isEmpty(), "unresolved candidates must be empty");
            }
            case AMBIGUOUS -> {
                Assert.isTrue(call.isEmpty(), "ambiguous call must be empty");
                Assert.isTrue(Set.copyOf(candidates).size() == candidates.size(),
                        "ambiguous candidates must be unique");
                Assert.isTrue(candidates.size() > 1, "ambiguous candidates are required");
            }
        }
    }

    public static SemanticCallResolution resolved(SemanticCall call) {
        return new SemanticCallResolution(SemanticCallResolutionStatus.RESOLVED, Optional.of(call), List.of());
    }

    public static SemanticCallResolution unresolved() {
        return new SemanticCallResolution(SemanticCallResolutionStatus.UNRESOLVED, Optional.empty(), List.of());
    }

    public static SemanticCallResolution ambiguous(List<SemanticMethod> candidates) {
        return new SemanticCallResolution(SemanticCallResolutionStatus.AMBIGUOUS, Optional.empty(), candidates);
    }

    private static List<SemanticMethod> normalizedCandidates(List<SemanticMethod> candidates) {
        return Objects.requireNonNull(candidates, "candidates are required").stream()
                .sorted(candidateOrder())
                .toList();
    }

    private static Comparator<SemanticMethod> candidateOrder() {
        return Comparator.comparing(SemanticMethod::packageName)
                .thenComparing(SemanticMethod::className)
                .thenComparing(SemanticMethod::methodName)
                .thenComparing(SemanticMethod::parameterTypes, SemanticCallResolution::compareParameters)
                .thenComparing(SemanticMethod::returnType)
                .thenComparing(method -> method.location().uri())
                .thenComparing(method -> method.location().range(), SemanticCallResolution::compareRanges)
                .thenComparing(method -> method.location().selectionRange(), SemanticCallResolution::compareRanges);
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

    private static int compareRanges(SemanticRange left, SemanticRange right) {
        int start = comparePositions(left.start(), right.start());
        if (start != 0) {
            return start;
        }
        return comparePositions(left.end(), right.end());
    }

    private static int comparePositions(SemanticPosition left, SemanticPosition right) {
        int line = Integer.compare(left.line(), right.line());
        if (line != 0) {
            return line;
        }
        return Integer.compare(left.character(), right.character());
    }
}
