package com.java.semantic.syntax.application;

import com.java.semantic.identity.JavaTypeIdentity;
import com.java.semantic.identity.MethodTarget;
import com.java.semantic.identity.SourceTypeIdentity;
import com.java.semantic.syntax.domain.AnalysisTargetStatus;
import com.java.semantic.syntax.domain.AnnotationEvidence;
import com.java.semantic.syntax.domain.SourceTypeMetadata;
import com.java.semantic.syntax.domain.SourceMethodMetadata;
import com.java.semantic.syntax.domain.SourceTypeKind;
import com.java.semantic.syntax.domain.MethodTargetResolution;
import com.java.semantic.syntax.domain.RepositorySyntax;
import com.java.semantic.syntax.domain.SourceSlice;
import com.java.semantic.syntax.domain.SyntaxPosition;
import com.java.semantic.syntax.domain.SyntaxRange;
import com.java.semantic.syntax.domain.NamedTypeReference;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventListenerDiscoveryPolicyTest {

    private static final String EVENT_TYPE = "com.acme.OrderPlaced";

    private final EventListenerDiscoveryPolicy policy = new EventListenerDiscoveryPolicy();

    @Test
    void should_match_unbound_written_event_listener_name() {
        EventListenerDiscoveryPage page = policy.discover(syntax(method(
                "onOrder", resolvedTarget("events/OrderListeners.java", "onOrder", List.of(EVENT_TYPE)),
                List.of(new AnnotationEvidence("org.springframework.context.event.EventListener", Optional.empty())))),
                EVENT_TYPE, 0, 50);

        assertThat(page.candidates().candidates()).singleElement().satisfies(candidate -> {
            assertThat(candidate.annotationEvidence()).singleElement().satisfies(evidence -> {
                assertThat(evidence.kind()).isEqualTo(ListenerAnnotationKind.EVENT_LISTENER);
                assertThat(evidence.matchKind()).isEqualTo(AnnotationMatchKind.WRITTEN_NAME);
            });
            assertThat(candidate.target()).isEqualTo(resolvedTarget(
                    "events/OrderListeners.java", "onOrder", List.of(EVENT_TYPE)));
        });
    }

    @Test
    void should_match_exact_resolved_spring_annotation_identity() {
        EventListenerDiscoveryPage page = policy.discover(syntax(method(
                "onOrder", resolvedTarget("events/OrderListeners.java", "onOrder", List.of(EVENT_TYPE)),
                List.of(resolvedAnnotation("internalAlias", "org.springframework.context.event", "EventListener")))),
                EVENT_TYPE, 0, 50);

        assertThat(page.candidates().candidates()).singleElement().satisfies(candidate ->
                assertThat(candidate.annotationEvidence()).singleElement().satisfies(evidence ->
                        assertThat(evidence.matchKind()).isEqualTo(AnnotationMatchKind.RESOLVED_IDENTITY)));
    }

    @Test
    void should_reject_resolved_non_spring_annotation_with_the_same_simple_name() {
        EventListenerDiscoveryPage page = policy.discover(syntax(method(
                "onOrder", resolvedTarget("events/OrderListeners.java", "onOrder", List.of(EVENT_TYPE)),
                List.of(resolvedAnnotation("EventListener", "com.acme", "EventListener")))), EVENT_TYPE, 0, 50);

        assertThat(page.candidates().candidates()).isEmpty();
    }

    @Test
    void should_not_allow_a_non_spring_annotation_to_veto_another_matching_annotation() {
        EventListenerDiscoveryPage page = policy.discover(syntax(method(
                "onOrder", resolvedTarget("events/OrderListeners.java", "onOrder", List.of(EVENT_TYPE)),
                List.of(
                        resolvedAnnotation("EventListener", "com.acme", "EventListener"),
                        resolvedAnnotation("TransactionalEventListener",
                                "org.springframework.transaction.event", "TransactionalEventListener")))),
                EVENT_TYPE, 0, 50);

        assertThat(page.candidates().candidates()).singleElement().satisfies(candidate ->
                assertThat(candidate.annotationEvidence())
                        .extracting(ListenerAnnotationEvidence::kind)
                        .containsExactly(ListenerAnnotationKind.TRANSACTIONAL_EVENT_LISTENER));
    }

    @Test
    void should_preserve_both_matching_annotation_evidence_in_protocol_order() {
        EventListenerDiscoveryPage page = policy.discover(syntax(method(
                "onOrder", resolvedTarget("events/OrderListeners.java", "onOrder", List.of(EVENT_TYPE)),
                List.of(
                        new AnnotationEvidence("TransactionalEventListener", Optional.empty()),
                        new AnnotationEvidence("EventListener", Optional.empty())))), EVENT_TYPE, 0, 50);

        assertThat(page.candidates().candidates()).singleElement().satisfies(candidate ->
                assertThat(candidate.annotationEvidence())
                        .extracting(ListenerAnnotationEvidence::kind)
                        .containsExactly(
                                ListenerAnnotationKind.EVENT_LISTENER,
                                ListenerAnnotationKind.TRANSACTIONAL_EVENT_LISTENER));
    }

    @Test
    void should_deduplicate_evidence_and_prefer_resolved_identity_for_each_annotation_kind() {
        EventListenerDiscoveryPage page = policy.discover(syntax(method(
                "onOrder", resolvedTarget("events/OrderListeners.java", "onOrder", List.of(EVENT_TYPE)),
                List.of(
                        new AnnotationEvidence("EventListener", Optional.empty()),
                        resolvedAnnotation("eventAlias", "org.springframework.context.event", "EventListener"),
                        new AnnotationEvidence("EventListener", Optional.empty()),
                        new AnnotationEvidence("TransactionalEventListener", Optional.empty())))), EVENT_TYPE, 0, 50);

        assertThat(page.candidates().candidates()).singleElement().satisfies(candidate ->
                assertThat(candidate.annotationEvidence()).containsExactly(
                        new ListenerAnnotationEvidence(
                                ListenerAnnotationKind.EVENT_LISTENER, AnnotationMatchKind.RESOLVED_IDENTITY),
                        new ListenerAnnotationEvidence(
                                ListenerAnnotationKind.TRANSACTIONAL_EVENT_LISTENER,
                                AnnotationMatchKind.WRITTEN_NAME)));
    }

    @Test
    void should_only_match_the_exact_canonical_event_parameter_and_reuse_target_identity() {
        MethodTarget target = resolvedTarget("events/OrderListeners.java", "onOrder", List.of(EVENT_TYPE));
        EventListenerDiscoveryPage page = policy.discover(syntax(method(
                "onOrder", target,
                List.of(new AnnotationEvidence("EventListener", Optional.empty())))), EVENT_TYPE, 0, 50);

        assertThat(page.candidates().candidates()).singleElement().satisfies(candidate -> {
            assertThat(candidate.target()).isSameAs(target);
            assertThat(candidate.declarationRange().sourceFile()).isEqualTo(target.sourceFile());
        });
    }

    @Test
    void should_not_match_an_array_parameter_or_parameter_type_reference() {
        MethodTarget target = resolvedTarget("events/OrderListeners.java", "onOrder", List.of(EVENT_TYPE + "[]"));
        EventListenerDiscoveryPage page = policy.discover(syntax(method(
                "onOrder", target,
                List.of(new AnnotationEvidence("EventListener", Optional.empty())))), EVENT_TYPE, 0, 50);

        assertThat(page.candidates().candidates()).isEmpty();
    }

    @Test
    void should_sort_then_paginate_safely_and_keep_total_count_complete() {
        RepositorySyntax syntax = syntax(
                method("z", resolvedTarget("z.java", "z", List.of(EVENT_TYPE)), eventListener()),
                method("a", resolvedTarget("a.java", "a", List.of(EVENT_TYPE)), eventListener()));

        EventListenerDiscoveryPage first = policy.discover(syntax, EVENT_TYPE, 0, 1);
        EventListenerDiscoveryPage second = policy.discover(syntax, EVENT_TYPE, 1, 1);
        EventListenerDiscoveryPage exact = policy.discover(syntax, EVENT_TYPE, 0, 2);
        EventListenerDiscoveryPage beyond = policy.discover(syntax, EVENT_TYPE, 9, 1);
        EventListenerDiscoveryPage overflow = policy.discover(syntax, EVENT_TYPE, Integer.MAX_VALUE, 100);
        EventListenerDiscoveryPage empty = policy.discover(RepositorySyntax.empty(), EVENT_TYPE, 0, 50);

        assertThat(first.candidates().candidates()).extracting(candidate -> candidate.target().sourceFile())
                .containsExactly("a.java");
        assertThat(first.candidates().totalCount()).isEqualTo(2);
        assertThat(first.candidates().hasMore()).isTrue();
        assertThat(second.candidates().candidates()).extracting(candidate -> candidate.target().sourceFile())
                .containsExactly("z.java");
        assertThat(second.candidates().returnedCount()).isOne();
        assertThat(second.candidates().totalCount()).isEqualTo(2);
        assertThat(second.candidates().hasMore()).isFalse();
        assertThat(exact.candidates().hasMore()).isFalse();
        assertThat(beyond.candidates().candidates()).isEmpty();
        assertThat(beyond.candidates().hasMore()).isFalse();
        assertThat(overflow.candidates().candidates()).isEmpty();
        assertThat(overflow.candidates().hasMore()).isFalse();
        assertThat(empty.candidates().candidates()).isEmpty();
        assertThat(empty.candidates().totalCount()).isZero();
        assertThat(empty.candidates().hasMore()).isFalse();
    }

    @Test
    void should_report_unresolved_and_ambiguous_listeners_under_one_complete_diagnostic() {
        MethodTarget firstCandidate = resolvedTarget("a.java", "first", List.of(EVENT_TYPE));
        MethodTarget secondCandidate = resolvedTarget("b.java", "second", List.of(EVENT_TYPE));
        SourceMethodMetadata unresolved = method(
                "unresolved", MethodTargetResolution.unresolved("BINDING_UNAVAILABLE"), eventListener());
        SourceMethodMetadata ambiguous = method("ambiguous", new MethodTargetResolution(
                AnalysisTargetStatus.AMBIGUOUS, Optional.empty(), List.of(firstCandidate, secondCandidate),
                "OVERLOAD_AMBIGUOUS"), eventListener());
        RepositorySyntax syntax = new RepositorySyntax(List.of(), List.of(
                metadata(unresolved, "module-b/src/main/java/com/acme/UnresolvedListener.java"),
                metadata(ambiguous, "module-a/src/main/java/com/acme/AmbiguousListener.java")));

        EventListenerDiscoveryPage page = policy.discover(syntax, EVENT_TYPE, 0, 50);

        assertThat(page.observations()).singleElement().satisfies(summary -> {
            assertThat(summary.code()).isEqualTo(ListenerObservationCode.LISTENER_TARGET_UNRESOLVED);
            assertThat(summary.totalCount()).isEqualTo(2);
            assertThat(summary.declarationRanges()).extracting(SourceRange::sourceFile)
                    .containsExactly(
                            "module-a/src/main/java/com/acme/AmbiguousListener.java",
                            "module-b/src/main/java/com/acme/UnresolvedListener.java");
        });
    }

    @Test
    void should_fail_when_a_recognized_resolved_listener_target_has_a_different_source_file() {
        SourceMethodMetadata method = method(
                "onOrder",
                resolvedTarget("module-a/src/main/java/com/acme/OrderListeners.java", "onOrder", List.of("com.acme.OtherEvent")),
                eventListener());
        RepositorySyntax syntax = new RepositorySyntax(List.of(), List.of(
                metadata(method, "module-b/src/main/java/com/acme/OrderListeners.java")));

        assertThatThrownBy(() -> policy.discover(syntax, EVENT_TYPE, 0, 50))
                .isInstanceOf(EventListenerDiscoveryContractException.class)
                .hasMessage("resolved listener target source file does not match syntax metadata");
    }

    @Test
    void should_require_candidate_annotation_evidence_and_matching_source_files() {
        MethodTarget target = resolvedTarget("events/OrderListeners.java", "onOrder", List.of(EVENT_TYPE));
        SourceRange matchingLocation = new SourceRange(target.sourceFile(), range());
        SourceRange mismatchedLocation = new SourceRange("events/Other.java", range());

        assertThatThrownBy(() -> new EventListenerCandidate(target, matchingLocation, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EventListenerCandidate(target, mismatchedLocation, List.of(
                new ListenerAnnotationEvidence(
                        ListenerAnnotationKind.EVENT_LISTENER, AnnotationMatchKind.WRITTEN_NAME))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void should_copy_candidate_annotation_evidence() {
        MethodTarget target = resolvedTarget("events/OrderListeners.java", "onOrder", List.of(EVENT_TYPE));
        List<ListenerAnnotationEvidence> evidence = new ArrayList<>();
        evidence.add(new ListenerAnnotationEvidence(
                ListenerAnnotationKind.EVENT_LISTENER, AnnotationMatchKind.WRITTEN_NAME));
        EventListenerCandidate candidate = new EventListenerCandidate(
                target, new SourceRange(target.sourceFile(), range()), evidence);

        evidence.clear();

        assertThat(candidate.annotationEvidence()).hasSize(1);
    }

    @Test
    void should_bound_sorted_diagnostic_samples_without_reducing_total_count() {
        EventListenerDiscoveryPage page = policy.discover(syntax(
                unresolvedMethod("f"),
                unresolvedMethod("e"),
                unresolvedMethod("d"),
                unresolvedMethod("c"),
                unresolvedMethod("b"),
                unresolvedMethod("a")), EVENT_TYPE, 0, 50);

        assertThat(page.observations()).singleElement().satisfies(summary -> {
            assertThat(summary.totalCount()).isEqualTo(6);
            assertThat(summary.declarationRanges()).extracting(SourceRange::sourceFile)
                    .containsExactly("a.java", "b.java", "c.java", "d.java", "e.java");
        });
    }

    private static List<AnnotationEvidence> eventListener() {
        return List.of(new AnnotationEvidence("EventListener", Optional.empty()));
    }

    private static AnnotationEvidence resolvedAnnotation(String writtenName, String packageName, String className) {
        return new AnnotationEvidence(writtenName, Optional.of(new JavaTypeIdentity(packageName, className)));
    }

    private static MethodTarget resolvedTarget(String sourceFile, String methodName, List<String> parameters) {
        return new MethodTarget(
                new SourceTypeIdentity(
                        new JavaTypeIdentity("com.acme.events", "OrderListeners"),
                        sourceFile),
                methodName,
                parameters);
    }

    private static RepositorySyntax syntax(SourceMethodMetadata... methods) {
        return new RepositorySyntax(List.of(), Arrays.stream(methods)
                .map(method -> metadata(method, sourceFileOf(method)))
                .toList());
    }

    private static SourceTypeMetadata metadata(SourceMethodMetadata method, String sourceFile) {
        return com.java.semantic.syntax.domain.SourceTypeMetadataFixture.sourceType(
                "OrderListeners", "com.acme.events", "com.acme.events.OrderListeners",
                sourceFile, SourceTypeKind.CLASS, false, List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(method), false, false, List.of(), range(), new SourceSlice(range(), ""),
                false, List.of());
    }

    private static String sourceFileOf(SourceMethodMetadata method) {
        return method.analysisTarget().target().map(MethodTarget::sourceFile).orElse(method.name() + ".java");
    }

    private static SourceMethodMetadata method(String name, MethodTarget target, List<AnnotationEvidence> annotations) {
        return method(name, MethodTargetResolution.resolved(target), annotations);
    }

    private static SourceMethodMetadata method(
            String name, MethodTargetResolution targetResolution, List<AnnotationEvidence> annotations) {
        return new SourceMethodMetadata(name, List.of("DifferentReference"), "", null, 3, 4,
                range(), new SourceSlice(range(), ""),
                List.of(new NamedTypeReference(EVENT_TYPE, EVENT_TYPE, Optional.empty(), false)), Optional.empty(), List.of(),
                annotations, List.of(), new SyntaxPosition(2, 4), targetResolution, true, false, false);
    }

    private static SourceMethodMetadata unresolvedMethod(String name) {
        return new SourceMethodMetadata(name, List.of(), "", null, 3, 4,
                range(), new SourceSlice(range(), ""), List.of(), Optional.empty(), List.of(), eventListener(),
                List.of(), new SyntaxPosition(2, 4), MethodTargetResolution.unresolved("BINDING_UNAVAILABLE"),
                true, false, false);
    }

    private static SyntaxRange range() {
        return new SyntaxRange(new SyntaxPosition(2, 0), new SyntaxPosition(3, 0));
    }
}
