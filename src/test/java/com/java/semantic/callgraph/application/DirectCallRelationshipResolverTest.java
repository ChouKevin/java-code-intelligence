package com.java.semantic.callgraph.application;

import com.java.semantic.callgraph.domain.ResolutionStrategy;
import com.java.semantic.identity.MethodTarget;
import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.semantic.domain.JavaSemanticService;
import com.java.semantic.semantic.domain.SemanticCall;
import com.java.semantic.semantic.domain.SemanticCallResolution;
import com.java.semantic.semantic.domain.SemanticCallSite;
import com.java.semantic.semantic.domain.SemanticCallStatus;
import com.java.semantic.semantic.domain.SemanticDeclarationAnchor;
import com.java.semantic.semantic.domain.SemanticIncomingCallResult;
import com.java.semantic.semantic.domain.SemanticLocation;
import com.java.semantic.semantic.domain.SemanticMethod;
import com.java.semantic.semantic.domain.SemanticPosition;
import com.java.semantic.semantic.domain.SemanticRange;
import com.java.semantic.semantic.domain.SemanticResolutionOrigin;
import com.java.semantic.syntax.domain.ClassMetadata;
import com.java.semantic.syntax.domain.ClassMetadata.MethodSignature;
import com.java.semantic.syntax.domain.MethodTargetResolution;
import com.java.semantic.syntax.domain.RepositorySyntax;
import com.java.semantic.syntax.domain.SourceSlice;
import com.java.semantic.syntax.domain.SyntaxInvocation;
import com.java.semantic.syntax.domain.SyntaxPosition;
import com.java.semantic.syntax.domain.SyntaxRange;
import com.java.semantic.syntax.domain.TypeReference;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.java.semantic.callgraph.application.SemanticGraphTestFixture.outgoingMethod;
import static com.java.semantic.callgraph.application.SemanticGraphTestFixture.resolvedCall;
import static com.java.semantic.callgraph.application.SemanticGraphTestFixture.target;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

class DirectCallRelationshipResolverTest {

    private static final RepositorySnapshot SNAPSHOT = new RepositorySnapshot(
            RepositoryId.of("orders"), Path.of("/fixture"), RepositoryRevision.fixture());

    @Test
    void should_resolve_local_external_ambiguous_and_unresolved_relationships_in_deterministic_order() {
        MethodTarget callerTarget = target("Root", "run");
        MethodTarget localTarget = target("Local", "work");
        MethodTarget interfaceTarget = target("Port", "handle");
        MethodTarget alphaTarget = target("AlphaPort", "handle");
        MethodTarget zetaTarget = target("ZetaPort", "handle");
        SemanticMethod caller = outgoingMethod(callerTarget, 0);
        SemanticMethod local = outgoingMethod(localTarget, 10);
        SemanticMethod declaration = outgoingMethod(interfaceTarget, 20);
        FakeSemanticService semantic = new FakeSemanticService()
                .outgoing(caller,
                        resolvedCall(local, 8),
                        externalCall("library.client()", 2),
                        resolvedCall(declaration, 4),
                        targetlessCall(6))
                .implementations(declaration, outgoingMethod(zetaTarget, 30), outgoingMethod(alphaTarget, 40));

        List<DirectCallRelationship> relationships = resolver(semantic).resolveAll(
                SNAPSHOT,
                index(type(callerTarget), type(localTarget), interfaceType(interfaceTarget), type(zetaTarget), type(alphaTarget)),
                callerTarget,
                caller);

        assertThat(relationships).extracting(DirectCallRelationship::status)
                .containsExactly(
                        DirectCallRelationship.Status.EXTERNAL,
                        DirectCallRelationship.Status.AMBIGUOUS,
                        DirectCallRelationship.Status.UNRESOLVED,
                        DirectCallRelationship.Status.LOCAL);
        assertThat(relationships.get(0).externalSymbol()).contains("library.client()");
        assertThat(relationships.get(1).candidates()).containsExactly(alphaTarget, zetaTarget);
        assertThat(relationships.get(2).evidence()).hasSize(0);
        assertThat(relationships.get(3)).satisfies(relationship -> {
            assertThat(relationship.target()).contains(localTarget);
            assertThat(relationship.semanticMethod()).contains(local);
            assertThat(relationship.strategy()).isEqualTo(ResolutionStrategy.JDT_CALL_HIERARCHY);
            assertThat(relationship.confidence()).isEqualTo(1.0d);
            assertThat(relationship.evidence()).containsExactly("work()");
        });
    }

    @Test
    void should_expose_declaration_target_and_invocation_when_interface_has_no_implementations() {
        MethodTarget callerTarget = target("Root", "run");
        MethodTarget interfaceTarget = target("Port", "handle");
        SemanticMethod caller = outgoingMethod(callerTarget, 0);
        SemanticMethod declaration = outgoingMethod(interfaceTarget, 10);
        FakeSemanticService semantic = new FakeSemanticService()
                .outgoing(caller, resolvedCall(declaration, 2));

        List<DirectCallRelationship> relationships = resolver(semantic).resolveAll(
                SNAPSHOT, index(type(callerTarget), interfaceType(interfaceTarget)), callerTarget, caller);

        assertThat(relationships).singleElement().satisfies(relationship -> {
            assertThat(relationship.status()).isEqualTo(DirectCallRelationship.Status.UNRESOLVED);
            assertThat(relationship.declarationTarget()).contains(interfaceTarget);
            assertThat(relationship.invocation()).isPresent();
        });
    }

    @Test
    void should_carry_invocation_without_declaration_target_when_definition_fallback_stays_unresolved() {
        MethodTarget callerTarget = target("Root", "run");
        SemanticMethod caller = outgoingMethod(callerTarget, 0);
        SyntaxInvocation invocation = invocation("worker.work()", 2, 3);
        FakeSemanticService semantic = new FakeSemanticService();

        List<DirectCallRelationship> relationships = resolver(semantic).resolveAll(
                SNAPSHOT, index(type(callerTarget, List.of(invocation))), callerTarget, caller);

        assertThat(relationships).singleElement().satisfies(relationship -> {
            assertThat(relationship.status()).isEqualTo(DirectCallRelationship.Status.UNRESOLVED);
            assertThat(relationship.invocation()).isPresent();
            assertThat(relationship.declarationTarget()).isNotPresent();
        });
    }

    @Test
    void should_use_definition_fallback_for_uncovered_syntax_invocations() {
        MethodTarget callerTarget = target("Root", "run");
        MethodTarget target = target("Local", "work");
        SemanticMethod caller = outgoingMethod(callerTarget, 0);
        SemanticMethod local = outgoingMethod(target, 10);
        SyntaxInvocation invocation = invocation("worker.work()", 2, 3);
        FakeSemanticService semantic = new FakeSemanticService().resolution(caller,
                SemanticCallResolution.resolved(resolvedCall(local, 2)));

        List<DirectCallRelationship> relationships = resolver(semantic).resolveAll(
                SNAPSHOT, index(type(callerTarget, List.of(invocation)), type(target)), callerTarget, caller);

        assertThat(relationships).singleElement().satisfies(relationship -> {
            assertThat(relationship.status()).isEqualTo(DirectCallRelationship.Status.LOCAL);
            assertThat(relationship.strategy()).isEqualTo(ResolutionStrategy.JDT_DEFINITION_FALLBACK);
            assertThat(relationship.confidence()).isEqualTo(0.9d);
            assertThat(relationship.expression()).isEqualTo("worker.work()");
            assertThat(relationship.evidence()).containsExactly("work()");
        });
    }

    @Test
    void should_retain_ordered_fallback_relationships_when_one_point_resolution_fails() {
        MethodTarget callerTarget = target("Root", "run");
        MethodTarget localTarget = target("Local", "work");
        SemanticMethod caller = outgoingMethod(callerTarget, 0);
        SemanticMethod local = outgoingMethod(localTarget, 10);
        SyntaxInvocation failedInvocation = invocation("worker.failed()", 2, 3);
        SyntaxInvocation successfulInvocation = invocation("worker.work()", 4, 5);
        SemanticCallSite failedCallSite = new SemanticCallSite(
                semanticRange(failedInvocation.range()), new SemanticPosition(2, 3));
        FakeSemanticService semantic = new FakeSemanticService()
                .resolution(caller, SemanticCallResolution.resolved(resolvedCall(local, 4)))
                .failResolutionAt(failedCallSite);

        Throwable throwable = catchThrowable(
                () -> resolver(semantic).resolveAll(
                        SNAPSHOT,
                        index(type(callerTarget, List.of(failedInvocation, successfulInvocation)), type(localTarget)),
                        callerTarget,
                        caller));

        assertThat(throwable).isInstanceOf(DirectCallRelationshipResolver.ResolutionFailure.class);
        DirectCallRelationshipResolver.ResolutionFailure failure =
                (DirectCallRelationshipResolver.ResolutionFailure) throwable;
        assertThat(failure.relationships()).extracting(DirectCallRelationship::status)
                .containsExactly(DirectCallRelationship.Status.UNRESOLVED, DirectCallRelationship.Status.LOCAL);
        assertThat(failure.relationships()).extracting(relationship -> relationship.callSite().start().line())
                .containsExactly(2, 4);
        assertThat(failure.relationships().get(1).target()).contains(localTarget);
        assertThat(failure.originals()).extracting(Throwable::getMessage)
                .containsExactly("planned point resolution failure");
    }

    @Test
    void should_rethrow_raw_point_resolution_failure_from_direct_resolution() {
        MethodTarget callerTarget = target("Root", "run");
        SemanticMethod caller = outgoingMethod(callerTarget, 0);
        SyntaxInvocation invocation = invocation("worker.failed()", 2, 3);
        SemanticCallSite callSite = new SemanticCallSite(
                semanticRange(invocation.range()), new SemanticPosition(2, 3));
        IllegalStateException expected = new IllegalStateException("planned point resolution failure");
        FakeSemanticService semantic = new FakeSemanticService().failResolutionAt(callSite, expected);

        assertThatThrownBy(() -> resolver(semantic).resolveAt(
                SNAPSHOT,
                index(type(callerTarget, List.of(invocation))),
                callerTarget,
                caller,
                semanticRange(invocation.range())))
                .isSameAs(expected);
    }

    @Test
    void should_continue_after_multiple_per_observation_resolution_failures_and_retain_ordered_relationships() {
        MethodTarget callerTarget = target("Root", "run");
        MethodTarget firstInterfaceTarget = target("FirstPort", "handle");
        MethodTarget localTarget = target("Local", "work");
        MethodTarget secondInterfaceTarget = target("SecondPort", "handle");
        SemanticMethod caller = outgoingMethod(callerTarget, 0);
        SemanticMethod firstDeclaration = outgoingMethod(firstInterfaceTarget, 10);
        SemanticMethod local = outgoingMethod(localTarget, 20);
        SemanticMethod secondDeclaration = outgoingMethod(secondInterfaceTarget, 30);
        FakeSemanticService semantic = new FakeSemanticService()
                .outgoing(caller, resolvedCall(firstDeclaration, 2), resolvedCall(local, 4), resolvedCall(secondDeclaration, 6))
                .failImplementations(firstDeclaration)
                .failImplementations(secondDeclaration);

        Throwable throwable = catchThrowable(
                () -> resolver(semantic).resolveAll(
                        SNAPSHOT,
                        index(
                                type(callerTarget),
                                interfaceType(firstInterfaceTarget),
                                type(localTarget),
                                interfaceType(secondInterfaceTarget)),
                        callerTarget,
                        caller));
        assertThat(throwable).isInstanceOf(DirectCallRelationshipResolver.ResolutionFailure.class);
        DirectCallRelationshipResolver.ResolutionFailure failure =
                (DirectCallRelationshipResolver.ResolutionFailure) throwable;

        assertThat(failure.relationships()).extracting(DirectCallRelationship::status)
                .containsExactly(
                        DirectCallRelationship.Status.UNRESOLVED,
                        DirectCallRelationship.Status.LOCAL,
                        DirectCallRelationship.Status.UNRESOLVED);
        assertThat(failure.relationships()).extracting(relationship -> relationship.callSite().start().line())
                .containsExactly(2, 4, 6);
        assertThat(failure.relationships().get(1).target()).contains(localTarget);
        assertThat(failure.originals()).extracting(Throwable::getMessage)
                .containsExactly("planned implementation query failure", "planned implementation query failure");
        assertThat(semantic.implementationQueries()).containsExactly(firstDeclaration, secondDeclaration);
    }

    @Test
    void should_resolve_at_only_with_the_exact_syntax_invocation_anchor() {
        MethodTarget callerTarget = target("Root", "run");
        MethodTarget target = target("Local", "work");
        SemanticMethod caller = outgoingMethod(callerTarget, 0);
        SemanticMethod local = outgoingMethod(target, 10);
        SyntaxInvocation nearby = invocation("worker.work()", 2, 1);
        SyntaxInvocation exact = invocation("worker.work()", 3, 7);
        FakeSemanticService semantic = new FakeSemanticService().resolution(caller,
                SemanticCallResolution.resolved(resolvedCall(local, 3)));
        DirectCallRelationshipResolver resolver = resolver(semantic);

        DirectCallRelationship relationship = resolver.resolveAt(
                SNAPSHOT,
                index(type(callerTarget, List.of(nearby, exact)), type(target)),
                callerTarget,
                caller,
                semanticRange(exact.range()));

        assertThat(relationship.status()).isEqualTo(DirectCallRelationship.Status.LOCAL);
        assertThat(semantic.lastCallSite()).contains(new SemanticCallSite(
                semanticRange(exact.range()), new SemanticPosition(3, 7)));
    }

    @Test
    void should_publish_the_full_syntax_invocation_when_incoming_call_hierarchy_uses_its_anchor() {
        MethodTarget callerTarget = target("Listener", "consume");
        MethodTarget target = target("OrderService", "placeFromMessage");
        SemanticMethod caller = outgoingMethod(callerTarget, 0);
        SemanticMethod local = outgoingMethod(target, 10);
        SyntaxRange invocationRange = range(3, 15, 3, 53);
        SemanticRange incomingCallSite = new SemanticRange(
                new SemanticPosition(3, 28), new SemanticPosition(3, 53));
        SyntaxInvocation invocation = new SyntaxInvocation(
                SyntaxInvocation.InvocationKind.METHOD,
                invocationRange,
                "orderService.placeFromMessage(orderId)",
                "orderService",
                "com.example.OrderService",
                "",
                Optional.empty(),
                new SyntaxPosition(3, 28));
        FakeSemanticService semantic = new FakeSemanticService().resolution(caller,
                SemanticCallResolution.resolved(resolvedCall(local, 3)));

        DirectCallRelationship relationship = resolver(semantic).resolveAt(
                SNAPSHOT,
                index(type(callerTarget, List.of(invocation)), type(target)),
                callerTarget,
                caller,
                incomingCallSite);

        assertThat(relationship.status()).isEqualTo(DirectCallRelationship.Status.LOCAL);
        assertThat(relationship.callSite()).isEqualTo(semanticRange(invocationRange));
        assertThat(semantic.lastCallSite()).contains(new SemanticCallSite(
                incomingCallSite, new SemanticPosition(3, 28)));
    }

    @Test
    void should_apply_anchor_aligned_syntax_invocation_qualifier_for_call_hierarchy_call_site() {
        MethodTarget callerTarget = target("Root", "run");
        MethodTarget interfaceTarget = target("Port", "handle");
        MethodTarget fastTarget = target("FastPort", "handle");
        MethodTarget slowTarget = target("SlowPort", "handle");
        SemanticMethod caller = outgoingMethod(callerTarget, 0);
        SemanticMethod declaration = outgoingMethod(interfaceTarget, 10);
        SemanticCall call = resolvedCall(declaration, 2);
        SyntaxInvocation fastInvocation = new SyntaxInvocation(
                SyntaxInvocation.InvocationKind.METHOD,
                range(2, 0, 2, 20),
                "port.handle()",
                "port",
                "com.example.Port",
                "fast",
                Optional.empty(),
                new SyntaxPosition(2, 0));
        FakeSemanticService semantic = new FakeSemanticService()
                .outgoing(caller, call)
                .implementations(declaration, outgoingMethod(fastTarget, 30), outgoingMethod(slowTarget, 40));

        List<DirectCallRelationship> relationships = resolver(semantic).resolveAll(
                SNAPSHOT,
                index(
                        type(callerTarget, List.of(fastInvocation)),
                        interfaceType(interfaceTarget),
                        qualifiedType(fastTarget, "fast"),
                        type(slowTarget)),
                callerTarget,
                caller);

        DirectCallRelationship hierarchyRelationship = relationships.stream()
                .filter(relationship -> relationship.callSite().equals(call.callSites().getFirst()))
                .findFirst()
                .orElseThrow();
        assertThat(hierarchyRelationship.status()).isEqualTo(DirectCallRelationship.Status.LOCAL);
        assertThat(hierarchyRelationship.target()).contains(fastTarget);
        assertThat(hierarchyRelationship.strategy()).isEqualTo(ResolutionStrategy.SPRING_BEAN_BY_QUALIFIER);
    }

    @Test
    void should_synthesize_blank_invocation_when_multiple_syntax_invocations_align_with_the_same_call_hierarchy_anchor() {
        MethodTarget callerTarget = target("Root", "run");
        MethodTarget interfaceTarget = target("Port", "handle");
        MethodTarget fastTarget = target("FastPort", "handle");
        MethodTarget slowTarget = target("SlowPort", "handle");
        SemanticMethod caller = outgoingMethod(callerTarget, 0);
        SemanticMethod declaration = outgoingMethod(interfaceTarget, 10);
        SemanticCall call = resolvedCall(declaration, 2);
        SyntaxInvocation first = new SyntaxInvocation(
                SyntaxInvocation.InvocationKind.METHOD, range(2, 0, 2, 20), "port.handle()",
                "port", "com.example.Port", "fast", Optional.empty(), new SyntaxPosition(2, 0));
        SyntaxInvocation second = new SyntaxInvocation(
                SyntaxInvocation.InvocationKind.METHOD, range(2, 0, 2, 15), "port.handle()",
                "port", "com.example.Port", "fast", Optional.empty(), new SyntaxPosition(2, 0));
        FakeSemanticService semantic = new FakeSemanticService()
                .outgoing(caller, call)
                .implementations(declaration, outgoingMethod(fastTarget, 30), outgoingMethod(slowTarget, 40));

        List<DirectCallRelationship> relationships = resolver(semantic).resolveAll(
                SNAPSHOT,
                index(
                        type(callerTarget, List.of(first, second)),
                        interfaceType(interfaceTarget),
                        qualifiedType(fastTarget, "fast"),
                        type(slowTarget)),
                callerTarget,
                caller);

        DirectCallRelationship hierarchyRelationship = relationships.stream()
                .filter(relationship -> relationship.callSite().equals(call.callSites().getFirst()))
                .findFirst()
                .orElseThrow();
        assertThat(hierarchyRelationship.status()).isEqualTo(DirectCallRelationship.Status.AMBIGUOUS);
        assertThat(hierarchyRelationship.candidates()).containsExactly(fastTarget, slowTarget);
    }

    @Test
    void should_not_guess_a_nearby_or_same_name_invocation_when_exact_call_site_is_missing() {
        MethodTarget callerTarget = target("Root", "run");
        SemanticMethod caller = outgoingMethod(callerTarget, 0);
        SyntaxInvocation nearby = invocation("worker.work()", 2, 1);
        FakeSemanticService semantic = new FakeSemanticService();

        DirectCallRelationship relationship = resolver(semantic).resolveAt(
                SNAPSHOT,
                index(type(callerTarget, List.of(nearby))),
                callerTarget,
                caller,
                new SemanticRange(new SemanticPosition(3, 0), new SemanticPosition(3, 4)));

        assertThat(relationship.status()).isEqualTo(DirectCallRelationship.Status.UNRESOLVED);
        assertThat(semantic.lastCallSite()).isNotPresent();
    }

    @Test
    void should_keep_mappable_ambiguous_candidates_when_only_some_candidates_map_to_syntax_targets() {
        MethodTarget callerTarget = target("Root", "run");
        MethodTarget alphaTarget = target("AlphaPort", "handle");
        MethodTarget betaTarget = target("BetaPort", "handle");
        MethodTarget unmappedTarget = target("GammaPort", "handle");
        SemanticMethod caller = outgoingMethod(callerTarget, 0);
        SemanticMethod alpha = outgoingMethod(alphaTarget, 10);
        SemanticMethod beta = outgoingMethod(betaTarget, 20);
        SemanticMethod unmapped = outgoingMethod(unmappedTarget, 30);
        SyntaxInvocation invocation = invocation("worker.handle()", 2, 3);
        FakeSemanticService semantic = new FakeSemanticService().resolution(
                caller, SemanticCallResolution.ambiguous(List.of(alpha, beta, unmapped)));

        List<DirectCallRelationship> relationships = resolver(semantic).resolveAll(
                SNAPSHOT,
                index(type(callerTarget, List.of(invocation)), type(alphaTarget), type(betaTarget)),
                callerTarget,
                caller);

        assertThat(relationships).singleElement().satisfies(relationship -> {
            assertThat(relationship.status()).isEqualTo(DirectCallRelationship.Status.AMBIGUOUS);
            assertThat(relationship.candidates()).containsExactly(alphaTarget, betaTarget);
        });
    }

    @Test
    void should_stay_unresolved_when_only_one_ambiguous_candidate_maps_to_a_syntax_target() {
        MethodTarget callerTarget = target("Root", "run");
        MethodTarget alphaTarget = target("AlphaPort", "handle");
        MethodTarget unmappedBetaTarget = target("BetaPort", "handle");
        MethodTarget unmappedGammaTarget = target("GammaPort", "handle");
        SemanticMethod caller = outgoingMethod(callerTarget, 0);
        SemanticMethod alpha = outgoingMethod(alphaTarget, 10);
        SemanticMethod unmappedBeta = outgoingMethod(unmappedBetaTarget, 20);
        SemanticMethod unmappedGamma = outgoingMethod(unmappedGammaTarget, 30);
        SyntaxInvocation invocation = invocation("worker.handle()", 2, 3);
        FakeSemanticService semantic = new FakeSemanticService().resolution(
                caller, SemanticCallResolution.ambiguous(List.of(alpha, unmappedBeta, unmappedGamma)));

        List<DirectCallRelationship> relationships = resolver(semantic).resolveAll(
                SNAPSHOT,
                index(type(callerTarget, List.of(invocation)), type(alphaTarget)),
                callerTarget,
                caller);

        assertThat(relationships).singleElement().satisfies(relationship -> {
            assertThat(relationship.status()).isEqualTo(DirectCallRelationship.Status.UNRESOLVED);
            assertThat(relationship.invocation()).isPresent();
        });
    }

    @Test
    void should_preserve_source_qualified_canonical_target_identity() {
        MethodTarget callerTarget = target("Root", "run");
        MethodTarget canonicalTarget = new MethodTarget(
                "src/main/java/com/example/Port.java", "com.example", "Port", "handle", List.of("com.example.Request"));
        SemanticMethod caller = outgoingMethod(callerTarget, 0);
        SemanticRange declarationRange = new SemanticRange(new SemanticPosition(0, 0), new SemanticPosition(5, 0));
        SemanticMethod semanticMethod = new SemanticMethod(
                "com.example", "Port", "handle", List.of("Request"), "void",
                new SemanticLocation("file:///fixture/src/main/java/com/example/Port.java", declarationRange, declarationRange));

        Optional<MethodTarget> resolved = resolver(new FakeSemanticService()).targetFor(
                SNAPSHOT, index(type(callerTarget), type(canonicalTarget)), semanticMethod);

        assertThat(resolved).contains(canonicalTarget);
        assertThat(caller).isNotNull();
    }

    private static DirectCallRelationshipResolver resolver(JavaSemanticService semanticService) {
        return new DirectCallRelationshipResolver(semanticService, new SpringImplementationSelector());
    }

    private static RepositorySyntaxIndex index(ClassMetadata... types) {
        return new RepositorySyntaxIndex(SNAPSHOT.repositoryId().value(), new RepositorySyntax(List.of(), List.of(types)));
    }

    private static ClassMetadata type(MethodTarget target) {
        return type(target, ClassMetadata.TypeKind.CLASS, true, List.of(), List.of());
    }

    private static ClassMetadata type(MethodTarget target, List<SyntaxInvocation> invocations) {
        return type(target, ClassMetadata.TypeKind.CLASS, true, invocations, List.of());
    }

    private static ClassMetadata interfaceType(MethodTarget target) {
        return type(target, ClassMetadata.TypeKind.INTERFACE, false, List.of(), List.of());
    }

    private static ClassMetadata qualifiedType(MethodTarget target, String qualifier) {
        return type(target, ClassMetadata.TypeKind.CLASS, true, List.of(), List.of(qualifier));
    }

    private static ClassMetadata type(
            MethodTarget target,
            ClassMetadata.TypeKind kind,
            boolean executableDeclaration,
            List<SyntaxInvocation> invocations,
            List<String> beanQualifiers) {
        SyntaxRange range = range(0, 0, 30, 0);
        SyntaxRange methodRange = range(0, 0, 5, 0);
        MethodSignature method = new MethodSignature(
                target.methodName(), target.parameterTypes(), List.of(), null, null, 1, 6,
                methodRange, new SourceSlice(methodRange, "void " + target.methodName() + "() {}"),
                List.<TypeReference>of(), Optional.empty(), invocations, List.of(), List.of(), methodRange.start(),
                MethodTargetResolution.resolved(target), executableDeclaration, true);
        return new ClassMetadata(
                target.className(), target.packageName(), target.packageName() + "." + target.className(),
                target.sourceFile(), kind, false, List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(method), false, false, List.of(), range,
                new SourceSlice(range, "class " + target.className() + " {}"), false, beanQualifiers);
    }

    private static SemanticCall externalCall(String rawSignature, int line) {
        SemanticRange range = new SemanticRange(new SemanticPosition(line, 0), new SemanticPosition(line, 4));
        return new SemanticCall(Optional.empty(), rawSignature, List.of(range), true,
                SemanticResolutionOrigin.CALL_HIERARCHY, SemanticCallStatus.IDENTITY_UNPROVEN);
    }

    private static SemanticCall targetlessCall(int line) {
        SemanticRange range = new SemanticRange(new SemanticPosition(line, 0), new SemanticPosition(line, 4));
        return new SemanticCall(Optional.empty(), "unknown()", List.of(range), false,
                SemanticResolutionOrigin.CALL_HIERARCHY, SemanticCallStatus.IDENTITY_UNPROVEN);
    }

    private static SyntaxInvocation invocation(String expression, int line, int anchorCharacter) {
        SyntaxRange range = range(line, 0, line, 4);
        return new SyntaxInvocation(SyntaxInvocation.InvocationKind.METHOD, range, expression,
                "worker", "", "", Optional.empty(), new SyntaxPosition(line, anchorCharacter));
    }

    private static SemanticRange semanticRange(SyntaxRange range) {
        return new SemanticRange(
                new SemanticPosition(range.start().line(), range.start().character()),
                new SemanticPosition(range.end().line(), range.end().character()));
    }

    private static SyntaxRange range(int startLine, int startCharacter, int endLine, int endCharacter) {
        return new SyntaxRange(
                new SyntaxPosition(startLine, startCharacter), new SyntaxPosition(endLine, endCharacter));
    }

    private static final class FakeSemanticService implements JavaSemanticService {

        private final Map<SemanticMethod, List<SemanticCall>> outgoing = new HashMap<>();
        private final List<SemanticMethod> failingImplementations = new ArrayList<>();
        private final Map<SemanticMethod, List<SemanticMethod>> implementations = new HashMap<>();
        private final Map<SemanticMethod, SemanticCallResolution> resolutions = new HashMap<>();
        private final Map<SemanticCallSite, RuntimeException> failingResolutions = new HashMap<>();
        private final List<SemanticMethod> implementationQueries = new ArrayList<>();
        private Optional<SemanticCallSite> lastCallSite = Optional.empty();

        FakeSemanticService outgoing(SemanticMethod caller, SemanticCall... calls) {
            outgoing.put(caller, List.of(calls));
            return this;
        }

        FakeSemanticService implementations(SemanticMethod declaration, SemanticMethod... methods) {
            implementations.put(declaration, List.of(methods));
            return this;
        }

        FakeSemanticService failImplementations(SemanticMethod declaration) {
            failingImplementations.add(declaration);
            return this;
        }

        FakeSemanticService resolution(SemanticMethod caller, SemanticCallResolution resolution) {
            resolutions.put(caller, resolution);
            return this;
        }

        FakeSemanticService failResolutionAt(SemanticCallSite callSite) {
            return failResolutionAt(callSite, new IllegalStateException("planned point resolution failure"));
        }

        FakeSemanticService failResolutionAt(SemanticCallSite callSite, RuntimeException failure) {
            failingResolutions.put(callSite, failure);
            return this;
        }

        Optional<SemanticCallSite> lastCallSite() {
            return lastCallSite;
        }

        List<SemanticMethod> implementationQueries() {
            return implementationQueries;
        }

        @Override
        public SemanticMethod resolveExactMethod(RepositorySnapshot snapshot, SemanticDeclarationAnchor anchor) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public SemanticCallResolution resolveCallResolutionAt(
                RepositorySnapshot snapshot, SemanticMethod caller, SemanticCallSite callSite) {
            lastCallSite = Optional.of(callSite);
            RuntimeException failure = failingResolutions.get(callSite);
            Optional.ofNullable(failure).ifPresent(exception -> {
                throw exception;
            });
            return resolutions.getOrDefault(caller, SemanticCallResolution.unresolved());
        }

        @Override
        public List<SemanticCall> outgoingCalls(RepositorySnapshot snapshot, SemanticMethod method) {
            return outgoing.getOrDefault(method, List.of());
        }

        @Override
        public SemanticIncomingCallResult incomingCalls(RepositorySnapshot snapshot, SemanticMethod callee) {
            return SemanticIncomingCallResult.empty();
        }

        @Override
        public List<SemanticMethod> implementations(RepositorySnapshot snapshot, SemanticMethod method) {
            implementationQueries.add(method);
            if (failingImplementations.contains(method)) {
                throw new IllegalStateException("planned implementation query failure");
            }
            return implementations.getOrDefault(method, List.of());
        }
    }
}
