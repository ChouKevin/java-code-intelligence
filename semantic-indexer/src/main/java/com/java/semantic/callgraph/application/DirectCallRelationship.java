package com.java.semantic.callgraph.application;

import com.java.semantic.callgraph.domain.ResolutionStrategy;
import com.java.semantic.identity.MethodTarget;
import com.java.semantic.semantic.domain.SemanticMethod;
import com.java.semantic.semantic.domain.SemanticRange;
import com.java.semantic.syntax.domain.SyntaxInvocation;
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
        List<String> evidence,
        List<MethodTarget> candidates,
        Optional<SyntaxInvocation> invocation,
        Optional<MethodTarget> declarationTarget) {

    DirectCallRelationship {
        status = Objects.requireNonNull(status, "status is required");
        target = Objects.requireNonNull(target, "target is required");
        semanticMethod = Objects.requireNonNull(semanticMethod, "semanticMethod is required");
        externalSymbol = Objects.requireNonNull(externalSymbol, "externalSymbol is required");
        callSite = Objects.requireNonNull(callSite, "callSite is required");
        Assert.hasText(expression, "expression is required");
        strategy = Objects.requireNonNull(strategy, "strategy is required");
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence is required"));
        candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates are required"));
        invocation = Objects.requireNonNull(invocation, "invocation is required");
        declarationTarget = Objects.requireNonNull(declarationTarget, "declarationTarget is required");
        assertPayload(status, target, semanticMethod, externalSymbol, candidates, invocation, declarationTarget);
    }

    static DirectCallRelationship local(
            MethodTarget target,
            SemanticMethod semanticMethod,
            SemanticRange callSite,
            String expression,
            ResolutionStrategy strategy,
            List<String> evidence) {
        return new DirectCallRelationship(
                Status.LOCAL,
                Optional.of(Objects.requireNonNull(target, "target is required")),
                Optional.of(Objects.requireNonNull(semanticMethod, "semanticMethod is required")),
                Optional.empty(),
                callSite,
                expression,
                strategy,
                evidence,
                List.of(),
                Optional.empty(),
                Optional.empty());
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
                evidence,
                List.of(),
                Optional.empty(),
                Optional.empty());
    }

    static DirectCallRelationship ambiguous(
            SemanticRange callSite,
            String expression,
            ResolutionStrategy strategy,
            List<MethodTarget> candidates) {
        return new DirectCallRelationship(
                Status.AMBIGUOUS,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                callSite,
                expression,
                strategy,
                List.of(),
                candidates,
                Optional.empty(),
                Optional.empty());
    }

    static DirectCallRelationship unresolved(
            SemanticRange callSite,
            String expression,
            ResolutionStrategy strategy) {
        return unresolved(callSite, expression, strategy, Optional.empty(), Optional.empty());
    }

    static DirectCallRelationship unresolved(
            SemanticRange callSite,
            String expression,
            ResolutionStrategy strategy,
            Optional<SyntaxInvocation> invocation,
            Optional<MethodTarget> declarationTarget) {
        return new DirectCallRelationship(
                Status.UNRESOLVED,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                callSite,
                expression,
                strategy,
                List.of(),
                List.of(),
                invocation,
                declarationTarget);
    }

    private static void assertPayload(
            Status status,
            Optional<MethodTarget> target,
            Optional<SemanticMethod> semanticMethod,
            Optional<String> externalSymbol,
            List<MethodTarget> candidates,
            Optional<SyntaxInvocation> invocation,
            Optional<MethodTarget> declarationTarget) {
        switch (status) {
            case LOCAL -> {
                Assert.isTrue(target.isPresent(), "local target is required");
                Assert.isTrue(semanticMethod.isPresent(), "local semanticMethod is required");
                Assert.isTrue(!externalSymbol.isPresent(), "local externalSymbol is forbidden");
                Assert.isTrue(CollectionUtils.isEmpty(candidates), "local candidates are forbidden");
                Assert.isTrue(!invocation.isPresent(), "local invocation is forbidden");
                Assert.isTrue(!declarationTarget.isPresent(), "local declarationTarget is forbidden");
            }
            case EXTERNAL -> {
                Assert.isTrue(!target.isPresent(), "external target is forbidden");
                Assert.isTrue(!semanticMethod.isPresent(), "external semanticMethod is forbidden");
                Assert.isTrue(externalSymbol.filter(StringUtils::hasText).isPresent(), "externalSymbol is required");
                Assert.isTrue(CollectionUtils.isEmpty(candidates), "external candidates are forbidden");
                Assert.isTrue(!invocation.isPresent(), "external invocation is forbidden");
                Assert.isTrue(!declarationTarget.isPresent(), "external declarationTarget is forbidden");
            }
            case AMBIGUOUS -> {
                Assert.isTrue(!target.isPresent(), "ambiguous target is forbidden");
                Assert.isTrue(!semanticMethod.isPresent(), "ambiguous semanticMethod is forbidden");
                Assert.isTrue(!externalSymbol.isPresent(), "ambiguous externalSymbol is forbidden");
                Assert.isTrue(candidates.size() > 1, "ambiguous candidates are required");
                Assert.isTrue(!invocation.isPresent(), "ambiguous invocation is forbidden");
                Assert.isTrue(!declarationTarget.isPresent(), "ambiguous declarationTarget is forbidden");
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
