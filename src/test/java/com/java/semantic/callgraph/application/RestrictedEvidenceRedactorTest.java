package com.java.semantic.callgraph.application;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.semantic.callgraph.domain.CallEdge;
import com.java.semantic.callgraph.domain.CallSiteRange;
import com.java.semantic.callgraph.domain.CallNode;
import com.java.semantic.callgraph.domain.CallNodeId;
import com.java.semantic.callgraph.domain.CallType;
import com.java.semantic.callgraph.domain.AnalysisError;
import com.java.semantic.callgraph.domain.EvidenceVisibility;
import com.java.semantic.callgraph.domain.ExplainableCallGraph;
import com.java.semantic.callgraph.domain.FlattenedCallGraph;
import com.java.semantic.callgraph.domain.FlattenedMethodNode;
import com.java.semantic.callgraph.domain.MethodId;
import com.java.semantic.callgraph.domain.ReadPolicy;
import com.java.semantic.callgraph.domain.ResolutionStrategy;
import com.java.semantic.callgraph.domain.TypeId;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RestrictedEvidenceRedactorTest {

    private static final String WARNING = "Business policy prohibits reading or summarizing this target";

    @Test
    void should_remove_all_forbidden_channels_from_domain_json_and_legacy_projection() throws Exception {
        MethodId rootId = method("public", "RootService", "run");
        MethodId secretId = method("secret", "VaultService", "open");
        CallNode root = node("node-1", rootId, "ROOT_SOURCE");
        CallNode secret = new CallNode(
                new CallNodeId("node-secret"), secretId, "com.acme.secret.VaultService.open()",
                CallType.INTERNAL_SERVICE, "SECRET_PATH_SENTINEL.java", 7, 9,
                Map.of("SECRET_ANNOTATION_SENTINEL", "SECRET_ANNOTATION_VALUE"),
                "SECRET_SOURCE_SENTINEL SECRET_SQL_SENTINEL SECRET_DTO_SENTINEL",
                EvidenceVisibility.READABLE);
        CallEdge edge = new CallEdge(
                root.nodeId(), secret.nodeId(), "SECRET_CALL_SENTINEL()",
                new CallSiteRange("SECRET_EDGE_SOURCE.java", 8, 1, 8, 2),
                ResolutionStrategy.JDT_CALL_HIERARCHY, 1.0,
                List.of("SECRET_EVIDENCE_SENTINEL"), List.of("SECRET_WARNING_SENTINEL"),
                EvidenceVisibility.READABLE);
        FlattenedMethodNode secretLegacy = new FlattenedMethodNode(
                secret.signature(), secretId.className(), secretId.methodName(), secret.callType(),
                "SECRET_LEGACY_DESCRIPTION", secret.code(), secret.annotations(), List.of());
        ExplainableCallGraph unsafe = new ExplainableCallGraph(
                rootId, List.of(root, secret), List.of(edge),
                Map.of("com.acme.secret.SecretDto", "SECRET_DTO_SENTINEL"),
                new FlattenedCallGraph(List.of(secretLegacy), root.signature(), Map.of()));

        ExplainableCallGraph redacted = new RestrictedEvidenceRedactor(forbidSecret()).redact(
                unsafe,
                List.of(new RelatedClassEvidence(
                        new TypeId("orders", "com.acme.secret", "SecretDto"),
                        "SECRET_DTO_SENTINEL")));
        String serialized = new ObjectMapper().writeValueAsString(redacted);

        assertThat(redacted.nodes()).filteredOn(
                value -> EvidenceVisibility.BUSINESS_READ_FORBIDDEN.equals(value.visibility())).singleElement()
                .satisfies(value -> {
                    assertThat(value.nodeId().value()).isEqualTo("restricted-node-1");
                    assertThat(value.methodId()).isNull();
                    assertThat(value.signature()).isEmpty();
                    assertThat(value.sourceFile()).isEmpty();
                    assertThat(value.startLine()).isNull();
                    assertThat(value.endLine()).isNull();
                    assertThat(value.annotations()).isEmpty();
                    assertThat(value.code()).isEmpty();
                });
        assertThat(redacted.nodes()).filteredOn(value -> EvidenceVisibility.READABLE.equals(value.visibility()))
                .singleElement().satisfies(value -> assertThat(value.code()).isEmpty());
        assertThat(redacted.edges()).singleElement().satisfies(value -> {
            assertThat(value.callExpression()).isEmpty();
            assertThat(value.callSite()).isNull();
            assertThat(value.sourceFile()).isEmpty();
            assertThat(value.lineNumber()).isNull();
            assertThat(value.resolutionStrategy()).isEqualTo(ResolutionStrategy.BUSINESS_READ_FORBIDDEN);
            assertThat(value.evidence()).isEmpty();
            assertThat(value.warnings()).containsExactly(WARNING);
            assertThat(value.visibility()).isEqualTo(EvidenceVisibility.BUSINESS_READ_FORBIDDEN);
        });
        assertThat(redacted.legacyFlattened().methods()).extracting(FlattenedMethodNode::signature)
                .containsExactly(root.signature());
        assertThat(serialized).doesNotContain(
                "VaultService", "SECRET_SOURCE_SENTINEL", "SECRET_SQL_SENTINEL", "SECRET_DTO_SENTINEL",
                "SECRET_ANNOTATION_SENTINEL", "SECRET_CALL_SENTINEL", "SECRET_EVIDENCE_SENTINEL",
                "SECRET_WARNING_SENTINEL", "SECRET_PATH_SENTINEL", "SECRET_LEGACY_DESCRIPTION");
    }

    @Test
    void should_assign_response_scoped_ordinals_by_encounter_order_without_identity_encoding() {
        MethodId rootId = method("public", "RootService", "run");
        CallNode root = node("node-1", rootId, "ROOT");
        CallNode second = node("hash", method("secret", "ZuluSecret", "z"), "SECRET_Z");
        CallNode first = node("path", method("secret", "AlphaSecret", "a"), "SECRET_A");
        ExplainableCallGraph unsafe = new ExplainableCallGraph(
                rootId, List.of(root, second, first), List.of(), Map.of(),
                new FlattenedCallGraph(List.of(), root.signature(), Map.of()));

        ExplainableCallGraph redacted = new RestrictedEvidenceRedactor(forbidSecret()).redact(unsafe, List.of());

        assertThat(redacted.nodes()).extracting(value -> value.nodeId().value())
                .containsExactly("node-1", "restricted-node-1", "restricted-node-2");
        assertThat(redacted.toString()).doesNotContain("ZuluSecret", "AlphaSecret", "SECRET_Z", "SECRET_A");
    }

    @Test
    void should_canonicalize_related_class_order_before_domain_and_json_output() throws Exception {
        MethodId rootId = method("public", "RootService", "run");
        CallNode root = node("node-1", rootId, "ROOT");
        Map<String, String> relatedClasses = new LinkedHashMap<>();
        relatedClasses.put("com.acme.public.ZetaDto", "zeta");
        relatedClasses.put("com.acme.public.AlphaDto", "alpha");
        relatedClasses.put("com.acme.public.ThetaDto", "theta");
        relatedClasses.put("com.acme.public.BetaDto", "beta");
        ExplainableCallGraph unsafe = new ExplainableCallGraph(
                rootId, List.of(root), List.of(), relatedClasses,
                new FlattenedCallGraph(List.of(), root.signature(), relatedClasses));

        ExplainableCallGraph redacted = new RestrictedEvidenceRedactor(forbidSecret()).redact(
                unsafe,
                List.of(
                        new RelatedClassEvidence(
                                new TypeId("orders", "com.acme.public", "ZetaDto"), "zeta"),
                        new RelatedClassEvidence(
                                new TypeId("orders", "com.acme.public", "AlphaDto"), "alpha"),
                        new RelatedClassEvidence(
                                new TypeId("orders", "com.acme.public", "ThetaDto"), "theta"),
                        new RelatedClassEvidence(
                                new TypeId("orders", "com.acme.public", "BetaDto"), "beta")));

        assertThat(redacted.relatedClasses().keySet()).containsExactly(
                "com.acme.public.AlphaDto",
                "com.acme.public.BetaDto",
                "com.acme.public.ThetaDto",
                "com.acme.public.ZetaDto");
        assertThat(redacted.legacyFlattened().relatedClasses().keySet())
                .containsExactlyElementsOf(redacted.relatedClasses().keySet());
        String serialized = new ObjectMapper().writeValueAsString(redacted);
        assertThat(serialized.indexOf("com.acme.public.AlphaDto"))
                .isLessThan(serialized.indexOf("com.acme.public.BetaDto"));
        assertThat(serialized.indexOf("com.acme.public.BetaDto"))
                .isLessThan(serialized.indexOf("com.acme.public.ThetaDto"));
        assertThat(serialized.indexOf("com.acme.public.ThetaDto"))
                .isLessThan(serialized.indexOf("com.acme.public.ZetaDto"));
    }

    @Test
    void should_redact_related_class_using_explicit_type_identity_without_splitting_fqn() throws Exception {
        MethodId rootId = method("public", "RootService", "run");
        CallNode root = node("node-1", rootId, "ROOT");
        String canonicalName = "com.acme.dto.Outer.Inner";
        TypeId exactType = new TypeId("orders", "com.acme.dto", "Outer.Inner");
        ExplainableCallGraph unsafe = new ExplainableCallGraph(
                rootId, List.of(root), List.of(), Map.of(canonicalName, "TYPE_SOURCE_SENTINEL"),
                new FlattenedCallGraph(List.of(), root.signature(), Map.of()));
        ReadPolicy policy = new ReadPolicy() {
            @Override
            public EvidenceVisibility visibilityOfRepository(String repositoryId) {
                return EvidenceVisibility.READABLE;
            }

            @Override
            public EvidenceVisibility visibilityOf(TypeId typeId) {
                return exactType.equals(typeId)
                        ? EvidenceVisibility.BUSINESS_READ_FORBIDDEN
                        : EvidenceVisibility.READABLE;
            }

            @Override
            public EvidenceVisibility visibilityOf(MethodId methodId) {
                return EvidenceVisibility.READABLE;
            }
        };

        ExplainableCallGraph redacted = new RestrictedEvidenceRedactor(policy)
                .redact(unsafe, List.of(new RelatedClassEvidence(exactType, "TYPE_SOURCE_SENTINEL")));
        String serialized = new ObjectMapper().writeValueAsString(redacted);

        assertThat(redacted.relatedClasses()).isEmpty();
        assertThat(redacted.legacyFlattened().relatedClasses()).isEmpty();
        assertThat(serialized).doesNotContain("TYPE_SOURCE_SENTINEL");
    }

    @Test
    void should_omit_related_class_when_source_key_and_type_identity_disagree() throws Exception {
        MethodId rootId = method("public", "RootService", "run");
        CallNode root = node("node-1", rootId, "ROOT");
        String restrictedCanonicalName = "com.acme.secret.RestrictedDto";
        String mismatchSentinel = "MISMATCHED_READABLE_IDENTITY_SENTINEL";
        String missingCanonicalName = "com.acme.public.MissingIdentityDto";
        String missingSentinel = "MISSING_IDENTITY_SENTINEL";
        Map<String, String> unsafeSources = new LinkedHashMap<>();
        unsafeSources.put(restrictedCanonicalName, mismatchSentinel);
        unsafeSources.put(missingCanonicalName, missingSentinel);
        ExplainableCallGraph unsafe = new ExplainableCallGraph(
                rootId, List.of(root), List.of(), unsafeSources,
                new FlattenedCallGraph(List.of(), root.signature(), unsafeSources));
        List<RelatedClassEvidence> mismatchedEvidence = new ArrayList<>();
        mismatchedEvidence.add(new RelatedClassEvidence(
                new TypeId("orders", "com.acme.public", "ReadableDto"), mismatchSentinel));

        ExplainableCallGraph redacted = new RestrictedEvidenceRedactor(forbidSecret())
                .redact(unsafe, mismatchedEvidence);
        mismatchedEvidence.clear();
        String serialized = new ObjectMapper().writeValueAsString(redacted);

        assertThat(redacted.relatedClasses()).isEmpty();
        assertThat(redacted.legacyFlattened().relatedClasses()).isEmpty();
        assertThat(redacted.toString()).doesNotContain(
                restrictedCanonicalName, mismatchSentinel, missingCanonicalName, missingSentinel, "ReadableDto");
        assertThat(serialized).doesNotContain(
                restrictedCanonicalName, mismatchSentinel, missingCanonicalName, missingSentinel, "ReadableDto");
    }

    @Test
    void should_omit_other_repository_evidence_from_domain_legacy_and_json() throws Exception {
        MethodId rootId = new MethodId(
                "repository-a", "com.acme.public", "RootService", "run", List.of());
        CallNode root = node("node-1", rootId, "ROOT");
        String canonicalName = "com.acme.public.SharedDto";
        String sentinel = "CROSS_REPOSITORY_EVIDENCE_SENTINEL";
        ExplainableCallGraph unsafe = new ExplainableCallGraph(
                rootId, List.of(root), List.of(), Map.of(canonicalName, sentinel),
                new FlattenedCallGraph(List.of(), root.signature(), Map.of(canonicalName, sentinel)));
        RelatedClassEvidence otherRepositoryEvidence = new RelatedClassEvidence(
                new TypeId("repository-b", "com.acme.public", "SharedDto"), sentinel);

        ExplainableCallGraph redacted = new RestrictedEvidenceRedactor(forbidSecret())
                .redact(unsafe, List.of(otherRepositoryEvidence));
        String serialized = new ObjectMapper().writeValueAsString(redacted);

        assertThat(redacted.relatedClasses()).containsExactlyInAnyOrderEntriesOf(Map.of());
        assertThat(redacted.legacyFlattened().relatedClasses()).containsExactlyInAnyOrderEntriesOf(Map.of());
        assertThat(redacted.toString()).doesNotContain(canonicalName, sentinel, "repository-b");
        assertThat(serialized).doesNotContain(canonicalName, sentinel, "repository-b");
    }


    @Test
    void should_log_only_sanitized_exception_type() {
        Logger logger = (Logger) LoggerFactory.getLogger(SemanticCallGraphBuilder.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        Level previous = logger.getLevel();
        logger.setLevel(Level.WARN);
        try {
            SemanticCallGraphBuilder.logChildFailure(
                    "orders", new IllegalStateException("SECRET_EXCEPTION_SENTINEL SECRET_LOG_SENTINEL"));
            AnalysisError error = SemanticCallGraphBuilder.childFailureError(
                    new IllegalStateException("SECRET_EXCEPTION_SENTINEL SECRET_LOG_SENTINEL"));

            assertThat(appender.list).singleElement().satisfies(event -> {
                assertThat(event.getFormattedMessage())
                        .contains("orders", "CHILD_SEMANTIC_QUERY_FAILED", "IllegalStateException")
                        .doesNotContain("SECRET_EXCEPTION_SENTINEL", "SECRET_LOG_SENTINEL");
                assertThat(event.getThrowableProxy()).isNull();
            });
            assertThat(error.toString())
                    .contains("CHILD_SEMANTIC_QUERY_FAILED", "IllegalStateException")
                    .doesNotContain("SECRET_EXCEPTION_SENTINEL", "SECRET_LOG_SENTINEL");
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previous);
            appender.stop();
        }
    }

    private static CallNode node(String nodeId, MethodId methodId, String code) {
        return new CallNode(
                new CallNodeId(nodeId), methodId,
                methodId.packageName() + "." + methodId.className() + "." + methodId.methodName() + "()",
                CallType.INTERNAL_SERVICE, methodId.className() + ".java", 1, 2, Map.of(), code,
                EvidenceVisibility.READABLE);
    }

    private static MethodId method(String packageName, String className, String methodName) {
        return new MethodId("orders", "com.acme." + packageName, className, methodName, List.of());
    }

    private static ReadPolicy forbidSecret() {
        return new ReadPolicy() {
            @Override
            public EvidenceVisibility visibilityOfRepository(String repositoryId) {
                return EvidenceVisibility.READABLE;
            }

            @Override
            public EvidenceVisibility visibilityOf(TypeId typeId) {
                return typeId.packageName().startsWith("com.acme.secret")
                        ? EvidenceVisibility.BUSINESS_READ_FORBIDDEN
                        : EvidenceVisibility.READABLE;
            }

            @Override
            public EvidenceVisibility visibilityOf(MethodId methodId) {
                return methodId.packageName().startsWith("com.acme.secret")
                        ? EvidenceVisibility.BUSINESS_READ_FORBIDDEN
                        : EvidenceVisibility.READABLE;
            }
        };
    }
}
