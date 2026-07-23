package com.java.semantic.callgraph.application;

import com.java.semantic.callgraph.domain.ResolutionStrategy;
import com.java.semantic.identity.MethodTarget;
import com.java.semantic.semantic.domain.SemanticMethod;
import com.java.semantic.semantic.domain.SemanticRange;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** One direct call-site outcome, normalized for outgoing and incoming traversal. */
record DirectCallRelationship(
        Status status,
        Optional<MethodTarget> target,
        Optional<SemanticMethod> semanticMethod,
        Optional<String> externalSymbol,
        SemanticRange callSite,
        String expression,
        ResolutionStrategy strategy,
        double confidence,
        List<String> evidence,
        List<MethodTarget> candidates) {

    DirectCallRelationship {
        status = Objects.requireNonNull(status, "status is required");
        target = Objects.requireNonNull(target, "target is required");
        semanticMethod = Objects.requireNonNull(semanticMethod, "semanticMethod is required");
        externalSymbol = Objects.requireNonNull(externalSymbol, "externalSymbol is required");
        callSite = Objects.requireNonNull(callSite, "callSite is required");
        Assert.hasText(expression, "expression is required");
        strategy = Objects.requireNonNull(strategy, "strategy is required");
        Assert.isTrue(confidence >= 0.0d && confidence <= 1.0d,
                "confidence must be between zero and one");
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence is required"));
        candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates are required"));
        assertPayload(status, target, semanticMethod, externalSymbol, candidates);
    }

    static DirectCallRelationship local(
            MethodTarget target,
            SemanticMethod semanticMethod,
            SemanticRange callSite,
            String expression,
            ResolutionStrategy strategy,
            double confidence,
            List<String> evidence) {
        return new DirectCallRelationship(
                Status.LOCAL,
                Optional.of(Objects.requireNonNull(target, "target is required")),
                Optional.of(Objects.requireNonNull(semanticMethod, "semanticMethod is required")),
                Optional.empty(),
                callSite,
                expression,
                strategy,
                confidence,
                evidence,
                List.of());
    }

    static DirectCallRelationship external(
            String externalSymbol,
            SemanticRange callSite,
            String expression,
            List<String> evidence) {
        Assert.hasText(externalSymbol, "externalSymbol is required");
        return new DirectCallRelationship(
                Status.EXTERNAL,
                Optional.empty(),
                Optional.empty(),
                Optional.of(externalSymbol),
                callSite,
                expression,
                ResolutionStrategy.EXTERNAL_LIBRARY,
                1.0d,
                evidence,
                List.of());
    }

    static DirectCallRelationship ambiguous(
            SemanticRange callSite,
            String expression,
            ResolutionStrategy strategy,
            double confidence,
            List<MethodTarget> candidates) {
        return new DirectCallRelationship(
                Status.AMBIGUOUS,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                callSite,
                expression,
                strategy,
                confidence,
                List.of(),
                candidates);
    }

    static DirectCallRelationship unresolved(
            SemanticRange callSite,
            String expression,
            ResolutionStrategy strategy,
            double confidence) {
        return new DirectCallRelationship(
                Status.UNRESOLVED,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                callSite,
                expression,
                strategy,
                confidence,
                List.of(),
                List.of());
    }

    private static void assertPayload(
            Status status,
            Optional<MethodTarget> target,
            Optional<SemanticMethod> semanticMethod,
            Optional<String> externalSymbol,
            List<MethodTarget> candidates) {
        switch (status) {
            case LOCAL -> {
                Assert.isTrue(target.isPresent(), "local target is required");
                Assert.isTrue(semanticMethod.isPresent(), "local semanticMethod is required");
                Assert.isTrue(!externalSymbol.isPresent(), "local externalSymbol is forbidden");
                Assert.isTrue(CollectionUtils.isEmpty(candidates), "local candidates are forbidden");
            }
            case EXTERNAL -> {
                Assert.isTrue(!target.isPresent(), "external target is forbidden");
                Assert.isTrue(!semanticMethod.isPresent(), "external semanticMethod is forbidden");
                Assert.isTrue(externalSymbol.filter(StringUtils::hasText).isPresent(), "externalSymbol is required");
                Assert.isTrue(CollectionUtils.isEmpty(candidates), "external candidates are forbidden");
            }
            case AMBIGUOUS -> {
                Assert.isTrue(!target.isPresent(), "ambiguous target is forbidden");
                Assert.isTrue(!semanticMethod.isPresent(), "ambiguous semanticMethod is forbidden");
                Assert.isTrue(!externalSymbol.isPresent(), "ambiguous externalSymbol is forbidden");
                Assert.isTrue(candidates.size() > 1, "ambiguous candidates are required");
            }
            case UNRESOLVED -> {
                Assert.isTrue(!target.isPresent(), "unresolved target is forbidden");
                Assert.isTrue(!semanticMethod.isPresent(), "unresolved semanticMethod is forbidden");
                Assert.isTrue(!externalSymbol.isPresent(), "unresolved externalSymbol is forbidden");
                Assert.isTrue(CollectionUtils.isEmpty(candidates), "unresolved candidates are forbidden");
            }
        }
    }

    enum Status {
        LOCAL,
        EXTERNAL,
        AMBIGUOUS,
        UNRESOLVED
    }
}
