package com.java.semantic.callgraph.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.semantic.callgraph.domain.CallEdge;
import com.java.semantic.callgraph.domain.CallSiteRange;
import com.java.semantic.callgraph.domain.CallNode;
import com.java.semantic.callgraph.domain.CallNodeId;
import com.java.semantic.callgraph.domain.CallType;
import com.java.semantic.callgraph.domain.EvidenceVisibility;
import com.java.semantic.callgraph.domain.ExplainableCallGraph;
import com.java.semantic.callgraph.domain.FlattenedCallGraph;
import com.java.semantic.callgraph.domain.MethodId;
import com.java.semantic.callgraph.domain.ReadPolicy;
import com.java.semantic.callgraph.domain.TypeId;
import com.java.semantic.callgraph.domain.ResolutionStrategy;
import com.java.semantic.syntax.domain.ClassMetadata;
import com.java.semantic.syntax.domain.ClassMetadata.FieldInfo;
import com.java.semantic.syntax.domain.ClassMetadata.MethodSignature;
import com.java.semantic.syntax.domain.ClassMetadata.SqlSource;
import com.java.semantic.syntax.domain.ClassMetadata.TypeKind;
import com.java.semantic.syntax.domain.RepositorySyntax;
import com.java.semantic.syntax.domain.SourceSlice;
import com.java.semantic.syntax.domain.SyntaxPosition;
import com.java.semantic.syntax.domain.SyntaxRange;
import com.java.semantic.syntax.domain.TypeReference;
import com.java.semantic.syntax.domain.AnnotationEvidence;
import com.java.semantic.syntax.domain.ResolvedTypeIdentity;
import com.java.semantic.syntax.domain.InvocationTarget;
import com.java.semantic.syntax.domain.SyntaxInvocation;
import com.java.semantic.syntax.domain.SyntaxInvocation.InvocationKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceEnricherTest {

    private static final String REPO = "orders";
    private static final MethodId ROOT = method("service", "OrderService", "load");
    private static final MethodId MAPPER = method("persistence", "OrderMapper", "find");

    @Test
    void should_prefer_annotation_sql_and_warn_when_data_access_has_no_sql() {
        CallNode root = node("node-1", ROOT, CallType.INTERNAL_SERVICE, "ROOT_SOURCE");
        CallNode mapper = node("node-2", MAPPER, CallType.DATA_ACCESS, "MAPPER_SOURCE");
        CallNode noSql = node(
                "node-3", method("persistence", "AuditMapper", "save"), CallType.DATA_ACCESS, "NO_SQL_SOURCE");
        ExplainableCallGraph graph = graph(root, mapper, noSql);
        RepositorySyntaxIndex index = new RepositorySyntaxIndex(REPO, new RepositorySyntax(List.of(), List.of(
                type(ROOT, methodSyntax(ROOT, "ROOT_SOURCE", null, null, List.of(), Optional.empty())),
                type(MAPPER, methodSyntax(
                        MAPPER, "MAPPER_SOURCE", "SELECT 'ANNOTATION_SQL_SENTINEL'", SqlSource.ANNOTATION,
                        List.of(), Optional.empty())),
                type(noSql.methodId(), methodSyntax(
                        noSql.methodId(), "NO_SQL_SOURCE", null, null, List.of(), Optional.empty())))));

        EvidenceEnricher.EnrichmentResult result = new EvidenceEnricher(readable()).enrich(
                graph, index, Map.of());

        assertThat(result.graph().nodes()).filteredOn(value -> MAPPER.equals(value.methodId())).singleElement()
                .satisfies(value -> assertThat(value.code()).isEqualTo("SELECT 'ANNOTATION_SQL_SENTINEL'"));
        assertThat(result.graph().edges()).filteredOn(value -> value.callee().equals(noSql.nodeId())).singleElement()
                .satisfies(value -> {
                    assertThat(value.resolutionStrategy()).isEqualTo(ResolutionStrategy.DATA_ACCESS_WITHOUT_EVIDENCE);
                    assertThat(value.warnings()).containsExactly(
                            "Data access target has no readable SQL evidence; do not infer query behavior");
                });
    }

    @Test
    void should_emit_full_cutoff_source_sorted_signatures_recursive_generic_dtos_and_lombok_fact()
            throws Exception {
        MethodId cutoffId = method("service", "CutoffService", "load");
        MethodId generatedId = method("dto", "OrderDto", "getLines");
        CallNode cutoff = node("node-1", cutoffId, CallType.TRAVERSAL_CUTOFF, "OLD");
        CallNode generated = node("node-2", generatedId, CallType.GENERATED_CODE, "");
        TypeReference line = typeReference("OrderLine", "com.example.dto.OrderLine", List.of());
        TypeReference list = typeReference("List<OrderLine>", "java.util.List", List.of(line));
        TypeReference map = typeReference(
                "Map<String,List<OrderLine>>", "java.util.Map",
                List.of(typeReference("String", "java.lang.String", List.of()), list));
        RepositorySyntaxIndex index = new RepositorySyntaxIndex(REPO, new RepositorySyntax(List.of(), List.of(
                type(cutoffId, methodSyntax(
                        cutoffId, "FULL_CUTOFF_SOURCE_SENTINEL", null, null, List.of(map), Optional.empty())),
                type(generatedId, methodSyntax(generatedId, "", null, null, List.of(), Optional.of(list))),
                dto("com.example.dto", "OrderLine", "DTO_LINE_SENTINEL", List.of(
                        new FieldInfo("parent", "OrderDto", List.of(), "", typeReference(
                                "OrderDto", "com.example.dto.OrderDto", List.of())))),
                dto("com.example.dto", "OrderDto", "DTO_ORDER_SENTINEL", List.of(
                        new FieldInfo("lines", "List", List.of(), "", list))))));
        EvidenceEnricher.MethodEvidence cutoffEvidence = new EvidenceEnricher.MethodEvidence(
                List.of("zeta()", "alpha()", "alpha()"), false);

        EvidenceEnricher.EnrichmentResult result = new EvidenceEnricher(readable()).enrich(
                graph(cutoff, generated), index, Map.of(cutoffId, cutoffEvidence));

        assertThat(result.graph().nodes()).filteredOn(value -> cutoffId.equals(value.methodId())).singleElement()
                .satisfies(value -> assertThat(value.code()).isEqualTo(
                        "FULL_CUTOFF_SOURCE_SENTINEL\n\nImmediate callees:\nalpha()\nzeta()"));
        assertThat(result.graph().relatedClasses().keySet()).containsExactly(
                "com.example.dto.OrderDto", "com.example.dto.OrderLine");
        assertThat(result.graph().legacyFlattened().relatedClasses().keySet()).containsExactly(
                "com.example.dto.OrderDto", "com.example.dto.OrderLine");
        String serialized = new ObjectMapper().writeValueAsString(result.graph());
        assertThat(serialized.indexOf("com.example.dto.OrderDto"))
                .isLessThan(serialized.indexOf("com.example.dto.OrderLine"));
        assertThat(result.graph().nodes()).filteredOn(value -> generatedId.equals(value.methodId())).singleElement()
                .satisfies(value -> {
                    assertThat(value.code()).isEmpty();
                    assertThat(value.annotations()).containsEntry(
                            "generatedEvidence",
                            "Lombok generated method com.example.dto.OrderDto.getLines(); no source body exists");
                });
        assertThat(result.graph().legacyFlattened().methods()).extracting(value -> value.signature())
                .containsExactly(cutoff.signature(), generated.signature());
    }

    @Test
    void should_omit_whole_cutoff_and_dto_slices_that_reference_forbidden_content() {
        MethodId cutoffId = method("service", "CutoffService", "load");
        TypeId secretDto = new TypeId(REPO, "com.example.secret", "SecretDto");
        TypeReference secret = typeReference("SecretDto", "com.example.secret.SecretDto", List.of());
        CallNode cutoff = node("node-1", cutoffId, CallType.TRAVERSAL_CUTOFF, "SOURCE_SECRET_SENTINEL");
        RepositorySyntaxIndex index = new RepositorySyntaxIndex(REPO, new RepositorySyntax(List.of(), List.of(
                type(cutoffId, methodSyntax(
                        cutoffId, "SOURCE_SECRET_SENTINEL", null, null, List.of(secret), Optional.empty())),
                dto("com.example.secret", "SecretDto", "DTO_SECRET_SENTINEL", List.of()))));
        ReadPolicy policy = forbidding(secretDto);

        EvidenceEnricher.EnrichmentResult result = new EvidenceEnricher(policy).enrich(
                graph(cutoff), index,
                Map.of(cutoffId, new EvidenceEnricher.MethodEvidence(List.of("SECRET_CALL_SENTINEL()"), true)));

        assertThat(result.graph().nodes()).singleElement().satisfies(value -> assertThat(value.code()).isEmpty());
        assertThat(result.graph().relatedClasses()).isEmpty();
        assertThat(result.graph().toString())
                .doesNotContain("SOURCE_SECRET_SENTINEL", "DTO_SECRET_SENTINEL", "SECRET_CALL_SENTINEL");
    }

    @Test
    void should_clear_code_and_annotations_for_forbidden_parameter_return_and_nested_generic_references()
            throws Exception {
        MethodId parameterId = method("service", "ParameterService", "load");
        MethodId returnId = method("service", "ReturnService", "load");
        MethodId genericId = method("service", "GenericService", "load");
        TypeId forbiddenType = new TypeId(REPO, "com.example.secret", "SecretDto");
        TypeReference secret = typeReference("SecretDto", "com.example.secret.SecretDto", List.of());
        TypeReference nestedGeneric = typeReference(
                "List<Map<String,SecretDto>>", "java.util.List", List.of(typeReference(
                        "Map<String,SecretDto>", "java.util.Map", List.of(
                                typeReference("String", "java.lang.String", List.of()), secret))));
        CallNode parameter = node("node-1", parameterId, CallType.INTERNAL_SERVICE, "OLD_PARAMETER");
        CallNode returned = node("node-2", returnId, CallType.INTERNAL_SERVICE, "OLD_RETURN");
        CallNode generic = node("node-3", genericId, CallType.INTERNAL_SERVICE, "OLD_GENERIC");
        RepositorySyntaxIndex index = new RepositorySyntaxIndex(REPO, new RepositorySyntax(List.of(), List.of(
                type(parameterId, methodSyntax(
                        parameterId, "FORBIDDEN_PARAMETER_SOURCE_SENTINEL", null, null, List.of(secret),
                        Optional.empty(), List.of("FORBIDDEN_PARAMETER_ANNOTATION_SENTINEL"))),
                type(returnId, methodSyntax(
                        returnId, "FORBIDDEN_RETURN_SOURCE_SENTINEL", null, null, List.of(),
                        Optional.of(secret), List.of("FORBIDDEN_RETURN_ANNOTATION_SENTINEL"))),
                type(genericId, methodSyntax(
                        genericId, "FORBIDDEN_GENERIC_SOURCE_SENTINEL", null, null, List.of(nestedGeneric),
                        Optional.empty(), List.of("FORBIDDEN_GENERIC_ANNOTATION_SENTINEL"))),
                dto("com.example.secret", "SecretDto", "FORBIDDEN_DTO_SOURCE_SENTINEL", List.of()))));

        EvidenceEnricher.EnrichmentResult result = new EvidenceEnricher(forbidding(forbiddenType)).enrich(
                graph(parameter, returned, generic), index, Map.of());
        String serialized = new ObjectMapper().writeValueAsString(result.graph());

        assertThat(result.graph().nodes()).allSatisfy(node -> {
            assertThat(node.signature()).isNotEmpty();
            assertThat(node.code()).isEmpty();
            assertThat(node.annotations()).isEmpty();
        });
        assertThat(result.graph().relatedClasses()).isEmpty();
        assertThat(result.graph().legacyFlattened().methods()).allSatisfy(node -> {
            assertThat(node.signature()).isNotEmpty();
            assertThat(node.code()).isEmpty();
            assertThat(node.annotations()).isEmpty();
        });
        assertThat(result.graph().toString()).doesNotContain(
                "FORBIDDEN_PARAMETER_SOURCE_SENTINEL", "FORBIDDEN_RETURN_SOURCE_SENTINEL",
                "FORBIDDEN_GENERIC_SOURCE_SENTINEL", "FORBIDDEN_DTO_SOURCE_SENTINEL",
                "FORBIDDEN_PARAMETER_ANNOTATION_SENTINEL", "FORBIDDEN_RETURN_ANNOTATION_SENTINEL",
                "FORBIDDEN_GENERIC_ANNOTATION_SENTINEL");
        assertThat(serialized).doesNotContain(
                "FORBIDDEN_PARAMETER_SOURCE_SENTINEL", "FORBIDDEN_RETURN_SOURCE_SENTINEL",
                "FORBIDDEN_GENERIC_SOURCE_SENTINEL", "FORBIDDEN_DTO_SOURCE_SENTINEL",
                "FORBIDDEN_PARAMETER_ANNOTATION_SENTINEL", "FORBIDDEN_RETURN_ANNOTATION_SENTINEL",
                "FORBIDDEN_GENERIC_ANNOTATION_SENTINEL");
    }

    @Test
    void should_remove_wildcard_bound_sentinels_from_graph_legacy_and_json() throws Exception {
        MethodId methodId = method("service", "WildcardService", "load");
        TypeReference secret = typeReference("SecretDto", "com.example.secret.SecretDto", List.of());
        TypeReference extendsWildcard = new TypeReference(
                "? extends SecretDto", "", List.of(), List.of(secret), List.of(), false);
        TypeReference superWildcard = new TypeReference(
                "? super SecretDto", "", List.of(), List.of(), List.of(secret), false);
        TypeReference parameter = typeReference(
                "List<? extends SecretDto>", "java.util.List", List.of(extendsWildcard));
        TypeReference returned = typeReference(
                "List<? super SecretDto>", "java.util.List", List.of(superWildcard));
        TypeReference fieldExtends = typeReference(
                "List<? extends SecretDto>", "java.util.List", List.of(extendsWildcard));
        TypeReference fieldSuper = typeReference(
                "List<? super SecretDto>", "java.util.List", List.of(superWildcard));
        String source = "WILDCARD_PARAMETER_SENTINEL WILDCARD_RETURN_SENTINEL";
        RepositorySyntaxIndex index = new RepositorySyntaxIndex(REPO, new RepositorySyntax(List.of(), List.of(
                type(methodId, methodSyntax(
                        methodId, source, null, null, List.of(parameter), Optional.of(returned))),
                dto("com.example.dto", "WildcardContainer", "WILDCARD_FIELD_EXTENDS_SENTINEL "
                        + "WILDCARD_FIELD_SUPER_SENTINEL", List.of(
                        new FieldInfo("extendsSecret", "List", List.of(), "", fieldExtends),
                        new FieldInfo("superSecret", "List", List.of(), "", fieldSuper))),
                dto("com.example.secret", "SecretDto", "WILDCARD_SECRET_SENTINEL", List.of()))));

        EvidenceEnricher.EnrichmentResult result = new EvidenceEnricher(
                forbidding(new TypeId(REPO, "com.example.secret", "SecretDto"))).enrich(
                        graph(node("node-1", methodId, CallType.INTERNAL_SERVICE, "OLD")), index, Map.of());

        String serialized = new ObjectMapper().writeValueAsString(result.graph());
        assertThat(result.graph().nodes()).singleElement().satisfies(node -> {
            assertThat(node.code()).isEmpty();
            assertThat(node.annotations()).isEmpty();
        });
        assertThat(result.graph().relatedClasses()).isEmpty();
        assertThat(result.graph().legacyFlattened().relatedClasses()).isEmpty();
        assertThat(result.graph().toString()).doesNotContain(
                "WILDCARD_PARAMETER_SENTINEL", "WILDCARD_RETURN_SENTINEL",
                "WILDCARD_FIELD_EXTENDS_SENTINEL", "WILDCARD_FIELD_SUPER_SENTINEL",
                "WILDCARD_SECRET_SENTINEL");
        assertThat(serialized).doesNotContain(
                "WILDCARD_PARAMETER_SENTINEL", "WILDCARD_RETURN_SENTINEL",
                "WILDCARD_FIELD_EXTENDS_SENTINEL", "WILDCARD_FIELD_SUPER_SENTINEL",
                "WILDCARD_SECRET_SENTINEL");
    }

    @Test
    void should_not_leak_resolved_annotation_or_invocation_target_through_graph_legacy_or_json() throws Exception {
        MethodId annotationId = method("service", "AnnotationService", "run");
        MethodId invocationId = method("service", "InvocationService", "run");
        MethodId invocationTypeId = method("service", "InvocationTypeService", "run");
        TypeId forbiddenType = new TypeId(REPO, "com.example.secret", "SecretMarker");
        TypeId forbiddenInvocationType = new TypeId(REPO, "com.example.secret", "TypeOnlyVault");
        MethodId forbiddenTarget = new MethodId(REPO, "com.example.secret", "Vault", "open", List.of());
        SyntaxRange range = range();
        MethodSignature annotationMethod = new MethodSignature(
                annotationId.methodName(), annotationId.parameterTypes(), List.of("SecretMarker"), null, null, 1, 2,
                range, new SourceSlice(range, "ANNOTATION_SOURCE_SENTINEL"), List.of(), Optional.empty(), List.of(),
                List.of(new AnnotationEvidence("SecretMarker", Optional.of(
                        new ResolvedTypeIdentity("com.example.secret", "SecretMarker")))));
        MethodSignature invocationMethod = new MethodSignature(
                invocationId.methodName(), invocationId.parameterTypes(), List.of(), null, null, 1, 2,
                range, new SourceSlice(range, "INVOCATION_SOURCE_SENTINEL"), List.of(), Optional.empty(),
                List.of(new SyntaxInvocation(
                        InvocationKind.METHOD, range,
                        "vault.open()", "vault", "", "", Optional.of(new InvocationTarget(
                                "com.example.secret", "Vault", "open", List.of())),
                        range.start())), List.of());
        MethodSignature invocationTypeMethod = new MethodSignature(
                invocationTypeId.methodName(), invocationTypeId.parameterTypes(), List.of(), null, null, 1, 2,
                range, new SourceSlice(range, "INVOCATION_TYPE_SOURCE_SENTINEL"), List.of(), Optional.empty(),
                List.of(new SyntaxInvocation(
                        InvocationKind.METHOD, range,
                        "typeOnlyVault.open()", "typeOnlyVault", "", "", Optional.of(new InvocationTarget(
                                "com.example.secret", "TypeOnlyVault", "open", List.of())),
                        range.start())), List.of());
        ReadPolicy policy = new ReadPolicy() {
            @Override
            public EvidenceVisibility visibilityOfRepository(String repositoryId) {
                return EvidenceVisibility.READABLE;
            }

            @Override
            public EvidenceVisibility visibilityOf(TypeId typeId) {
                return forbiddenType.equals(typeId) || forbiddenInvocationType.equals(typeId)
                        ? EvidenceVisibility.BUSINESS_READ_FORBIDDEN
                        : EvidenceVisibility.READABLE;
            }

            @Override
            public EvidenceVisibility visibilityOf(MethodId methodId) {
                return forbiddenTarget.equals(methodId) ? EvidenceVisibility.BUSINESS_READ_FORBIDDEN
                        : EvidenceVisibility.READABLE;
            }
        };
        RepositorySyntaxIndex index = new RepositorySyntaxIndex(
                REPO, new RepositorySyntax(List.of(), List.of(
                        type(annotationId, annotationMethod), type(invocationId, invocationMethod),
                        type(invocationTypeId, invocationTypeMethod))));

        ExplainableCallGraph enriched = new EvidenceEnricher(policy)
                .enrich(graph(
                        node("node-1", annotationId, CallType.INTERNAL_SERVICE, "OLD_ANNOTATION"),
                        node("node-2", invocationId, CallType.INTERNAL_SERVICE, "OLD_INVOCATION"),
                        node("node-3", invocationTypeId, CallType.INTERNAL_SERVICE, "OLD_INVOCATION_TYPE")),
                        index, Map.of())
                .graph();
        String json = new ObjectMapper().writeValueAsString(enriched);

        assertThat(enriched.nodes()).allSatisfy(node -> {
            assertThat(node.code()).isEmpty();
            assertThat(node.annotations()).isEmpty();
        });
        assertThat(enriched.legacyFlattened().methods()).allSatisfy(node -> {
            assertThat(node.code()).isEmpty();
            assertThat(node.annotations()).isEmpty();
        });
        assertThat(json).doesNotContain(
                "ANNOTATION_SOURCE_SENTINEL", "INVOCATION_SOURCE_SENTINEL", "INVOCATION_TYPE_SOURCE_SENTINEL",
                "SecretMarker", "vault.open()", "typeOnlyVault.open()");
    }

    @Test
    void should_omit_every_body_type_reference_slice_without_leaking_each_sentinel() throws Exception {
        List<BodyTypeSentinel> forbiddenReferences = List.of(
                new BodyTypeSentinel("LOCAL_DECLARATION_SENTINEL", "ForbiddenLocal"),
                new BodyTypeSentinel("CAST_SENTINEL", "ForbiddenCast"),
                new BodyTypeSentinel("INSTANCEOF_SENTINEL", "ForbiddenInstanceof"),
                new BodyTypeSentinel("CLASS_LITERAL_SENTINEL", "ForbiddenClassLiteral"),
                new BodyTypeSentinel("FIELD_ACCESS_SENTINEL", "SecretHolder"),
                new BodyTypeSentinel("FIELD_VALUE_SENTINEL", "ForbiddenFieldValue"),
                new BodyTypeSentinel("GENERIC_FIELD_VALUE_SENTINEL", "ForbiddenGenericFieldValue"),
                new BodyTypeSentinel("BARE_FIELD_ACCESS_SENTINEL", "ForbiddenBareFieldValue"),
                new BodyTypeSentinel("ANNOTATION_MEMBER_CLASS_LITERAL_SENTINEL", "ForbiddenAnnotationMember"));

        for (BodyTypeSentinel sentinel : forbiddenReferences) {
            MethodId forbiddenMethod = method("service", "BodyEvidenceService", sentinel.className());
            MethodId readableMethod = method("service", "ReadableEvidenceService", sentinel.className());
            MethodSignature forbiddenSyntax = methodSyntax(
                    forbiddenMethod, sentinel.sentinel(), null, null, List.of(), Optional.empty(),
                    List.of("@RestrictedAnnotation(" + sentinel.sentinel() + ")"),
                    List.of(new ResolvedTypeIdentity("com.example.secret", sentinel.className())));
            MethodSignature readableSyntax = methodSyntax(
                    readableMethod, "READABLE_BODY_EVIDENCE_SIBLING", null, null, List.of(), Optional.empty(),
                    List.of(), List.of());
            RepositorySyntaxIndex index = new RepositorySyntaxIndex(REPO, new RepositorySyntax(List.of(), List.of(
                    type(forbiddenMethod, forbiddenSyntax), type(readableMethod, readableSyntax))));
            ExplainableCallGraph graph = graph(
                    node("restricted", forbiddenMethod, CallType.INTERNAL_SERVICE, "OLD_RESTRICTED"),
                    node("readable", readableMethod, CallType.INTERNAL_SERVICE, "OLD_READABLE"));

            ExplainableCallGraph enriched = new EvidenceEnricher(forbidding(
                    new TypeId(REPO, "com.example.secret", sentinel.className())))
                    .enrich(graph, index, Map.of())
                    .graph();
            String serialized = new ObjectMapper().writeValueAsString(enriched);

            assertThat(enriched.nodes()).filteredOn(node -> forbiddenMethod.equals(node.methodId())).singleElement()
                    .satisfies(node -> {
                        assertThat(node.code()).hasSize(0);
                        assertThat(node.annotations()).hasSize(0);
                    });
            assertThat(enriched.nodes()).filteredOn(node -> readableMethod.equals(node.methodId())).singleElement()
                    .satisfies(node -> assertThat(node.code()).isEqualTo("READABLE_BODY_EVIDENCE_SIBLING"));
            assertThat(enriched.legacyFlattened().methods())
                    .filteredOn(node -> "BodyEvidenceService".equals(node.className()))
                    .singleElement()
                    .satisfies(node -> assertThat(node.code()).hasSize(0));
            assertThat(enriched.legacyFlattened().methods())
                    .filteredOn(node -> "ReadableEvidenceService".equals(node.className()))
                    .singleElement()
                    .satisfies(node -> assertThat(node.code()).isEqualTo("READABLE_BODY_EVIDENCE_SIBLING"));
            assertThat(enriched.toString()).doesNotContain(sentinel.sentinel());
            assertThat(serialized).doesNotContain(sentinel.sentinel());
        }
    }

    @Test
    void should_preserve_safe_method_slice_when_sibling_method_is_forbidden() throws Exception {
        MethodId safeMethod = method("service", "SharedService", "safe");
        MethodId forbiddenMethod = method("service", "SharedService", "forbidden");
        MethodSignature safeSyntax = methodSyntax(
                safeMethod, "SAFE_METHOD_BODY_SENTINEL", null, null, List.of(), Optional.empty(),
                List.of("@SafeMethodAnnotation"));
        MethodSignature forbiddenSyntax = methodSyntax(
                forbiddenMethod, "FORBIDDEN_METHOD_BODY_SENTINEL", null, null, List.of(), Optional.empty(),
                List.of("@ForbiddenMethodAnnotation"));
        RepositorySyntaxIndex index = new RepositorySyntaxIndex(REPO, new RepositorySyntax(
                List.of(), List.of(metadata(
                        "com.example.service", "SharedService", "class SharedService {}", List.of(),
                        List.of(safeSyntax, forbiddenSyntax)))));
        ReadPolicy policy = new ReadPolicy() {
            @Override
            public EvidenceVisibility visibilityOfRepository(String repositoryId) {
                return EvidenceVisibility.READABLE;
            }

            @Override
            public EvidenceVisibility visibilityOf(TypeId typeId) {
                return EvidenceVisibility.READABLE;
            }

            @Override
            public EvidenceVisibility visibilityOf(MethodId methodId) {
                return forbiddenMethod.equals(methodId)
                        ? EvidenceVisibility.BUSINESS_READ_FORBIDDEN
                        : EvidenceVisibility.READABLE;
            }
        };
        ExplainableCallGraph graph = new ExplainableCallGraph(
                safeMethod,
                List.of(
                        node("safe", safeMethod, CallType.INTERNAL_SERVICE, "OLD_SAFE"),
                        node("forbidden", forbiddenMethod, CallType.INTERNAL_SERVICE, "OLD_FORBIDDEN")),
                List.of(), Map.of(), new FlattenedCallGraph(List.of(), safeMethod.methodName() + "()", Map.of()));

        EvidenceEnricher.EnrichmentResult enrichment = new EvidenceEnricher(policy).enrich(graph, index, Map.of());
        ExplainableCallGraph redacted = new RestrictedEvidenceRedactor(policy).redact(
                enrichment.graph(), enrichment.relatedClassEvidence());
        String serialized = new ObjectMapper().writeValueAsString(redacted);

        assertThat(enrichment.graph().nodes()).filteredOn(node -> safeMethod.equals(node.methodId())).singleElement()
                .satisfies(node -> {
                    assertThat(node.code()).isEqualTo("SAFE_METHOD_BODY_SENTINEL");
                    assertThat(node.annotations()).containsExactlyEntriesOf(Map.of(
                            "annotation-1", "@SafeMethodAnnotation"));
                });
        assertThat(redacted.nodes()).filteredOn(node ->
                EvidenceVisibility.BUSINESS_READ_FORBIDDEN.equals(node.visibility()))
                .singleElement().satisfies(node -> {
                    assertThat(node.methodId()).isNull();
                    assertThat(node.code()).isEmpty();
                    assertThat(node.annotations()).isEmpty();
                });
        assertThat(redacted.legacyFlattened().methods()).singleElement().satisfies(node -> {
            assertThat(node.code()).isEqualTo("SAFE_METHOD_BODY_SENTINEL");
            assertThat(node.annotations()).containsExactlyEntriesOf(Map.of(
                    "annotation-1", "@SafeMethodAnnotation"));
        });
        assertThat(redacted.toString()).contains("SAFE_METHOD_BODY_SENTINEL", "@SafeMethodAnnotation")
                .doesNotContain("FORBIDDEN_METHOD_BODY_SENTINEL", "@ForbiddenMethodAnnotation");
        assertThat(serialized).contains("SAFE_METHOD_BODY_SENTINEL", "@SafeMethodAnnotation")
                .doesNotContain("FORBIDDEN_METHOD_BODY_SENTINEL", "@ForbiddenMethodAnnotation");
    }

    @Test
    void should_omit_related_class_when_one_actual_method_is_forbidden_without_fake_method_identity()
            throws Exception {
        MethodId rootId = method("service", "RootService", "run");
        MethodId readableFakeName = method("dto", "Outer.Inner", "typeEvidence");
        MethodId forbiddenMethod = method("dto", "Outer.Inner", "secret");
        TypeReference relatedType = typeReference(
                "Outer.Inner", "com.example.dto.Outer.Inner", List.of());
        ClassMetadata related = metadata(
                "com.example.dto", "Outer.Inner", "EXACT_METHOD_BODY_SENTINEL",
                List.of(), List.of(
                        methodSyntax(readableFakeName, "readable", null, null, List.of(), Optional.empty()),
                        methodSyntax(forbiddenMethod, "EXACT_METHOD_BODY_SENTINEL", null, null,
                                List.of(), Optional.empty())));
        RepositorySyntaxIndex index = new RepositorySyntaxIndex(REPO, new RepositorySyntax(
                List.of(), List.of(
                        type(rootId, methodSyntax(
                                rootId, "root", null, null, List.of(), Optional.of(relatedType))),
                        related)));
        ReadPolicy exactMethodPolicy = new ReadPolicy() {
            @Override
            public EvidenceVisibility visibilityOfRepository(String repositoryId) {
                return EvidenceVisibility.READABLE;
            }

            @Override
            public EvidenceVisibility visibilityOf(TypeId typeId) {
                return EvidenceVisibility.READABLE;
            }

            @Override
            public EvidenceVisibility visibilityOf(MethodId methodId) {
                return forbiddenMethod.equals(methodId)
                        ? EvidenceVisibility.BUSINESS_READ_FORBIDDEN
                        : EvidenceVisibility.READABLE;
            }
        };

        EvidenceEnricher.EnrichmentResult result = new EvidenceEnricher(exactMethodPolicy).enrich(
                graph(node("node-1", rootId, CallType.INTERNAL_SERVICE, "root")), index, Map.of());
        String serialized = new ObjectMapper().writeValueAsString(result.graph());

        assertThat(result.graph().relatedClasses()).isEmpty();
        assertThat(result.graph().legacyFlattened().relatedClasses()).isEmpty();
        assertThat(result.graph().toString()).doesNotContain("EXACT_METHOD_BODY_SENTINEL");
        assertThat(serialized).doesNotContain("EXACT_METHOD_BODY_SENTINEL");
    }

    @Test
    void should_preserve_annotation_insertion_order_through_enrichment_redaction_legacy_and_json()
            throws Exception {
        MethodId rootId = method("service", "RootService", "run");
        List<String> annotations = List.of(
                "@Zulu", "@Alpha", "@Theta", "@Beta", "@Eta", "@Gamma", "@Delta", "@Epsilon");
        SyntaxRange range = range();
        MethodSignature method = new MethodSignature(
                rootId.methodName(), rootId.parameterTypes(), annotations, null, null, 1, 2,
                range, new SourceSlice(range, "root"), List.of(), Optional.empty(), List.of());
        RepositorySyntaxIndex index = new RepositorySyntaxIndex(
                REPO, new RepositorySyntax(List.of(), List.of(type(rootId, method))));
        EvidenceEnricher.EnrichmentResult enrichment = new EvidenceEnricher(readable()).enrich(
                graph(node("node-1", rootId, CallType.INTERNAL_SERVICE, "root")), index, Map.of());
        ExplainableCallGraph result = new RestrictedEvidenceRedactor(readable()).redact(
                enrichment.graph(), enrichment.relatedClassEvidence());
        List<String> expectedKeys = List.of(
                "annotation-1", "annotation-2", "annotation-3", "annotation-4",
                "annotation-5", "annotation-6", "annotation-7", "annotation-8");
        String serialized = new ObjectMapper().writeValueAsString(result);

        assertThat(result.nodes().getFirst().annotations().keySet()).containsExactlyElementsOf(expectedKeys);
        assertThat(result.legacyFlattened().methods().getFirst().annotations().keySet())
                .containsExactlyElementsOf(expectedKeys);
        for (int indexPosition = 1; indexPosition < expectedKeys.size(); indexPosition++) {
            assertThat(serialized.indexOf(expectedKeys.get(indexPosition - 1)))
                    .isLessThan(serialized.indexOf(expectedKeys.get(indexPosition)));
        }
    }

    private static ExplainableCallGraph graph(CallNode... nodes) {
        List<CallEdge> edges = IntStream.range(1, nodes.length)
                .mapToObj(index -> new CallEdge(
                        nodes[0].nodeId(), nodes[index].nodeId(), "call()",
                        new CallSiteRange("Root.java", index + 1, 1, index + 1, 2),
                        ResolutionStrategy.JDT_CALL_HIERARCHY, 1.0, List.of(), List.of(),
                        EvidenceVisibility.READABLE))
                .toList();
        return new ExplainableCallGraph(
                nodes[0].methodId(), List.of(nodes), edges, Map.of(), new FlattenedCallGraph(List.of(), "", Map.of()));
    }

    private static CallNode node(String id, MethodId methodId, CallType callType, String code) {
        return new CallNode(
                new CallNodeId(id), methodId,
                methodId.packageName() + "." + methodId.className() + "." + methodId.methodName() + "()",
                callType, methodId.className() + ".java", 1, 2, Map.of(), code, EvidenceVisibility.READABLE);
    }

    private static ClassMetadata type(MethodId methodId, MethodSignature method) {
        return metadata(methodId.packageName(), methodId.className(), "class " + methodId.className() + " {}",
                List.of(), List.of(method));
    }

    private static ClassMetadata dto(
            String packageName, String className, String source, List<FieldInfo> fields) {
        return metadata(packageName, className, source, fields, List.of());
    }

    private static ClassMetadata metadata(
            String packageName,
            String className,
            String source,
            List<FieldInfo> fields,
            List<MethodSignature> methods) {
        SyntaxRange range = range();
        return new ClassMetadata(
                className, packageName, packageName + "." + className,
                className + ".java", TypeKind.CLASS, false, List.of(), List.of(), List.of(), List.of(), fields,
                methods, false, false, List.of(), range, new SourceSlice(range, source), false, List.of());
    }

    private static MethodSignature methodSyntax(
            MethodId methodId,
            String source,
            String sql,
            SqlSource sqlSource,
            List<TypeReference> parameters,
            Optional<TypeReference> returnType) {
        return methodSyntax(methodId, source, sql, sqlSource, parameters, returnType,
                List.of("SECRET_ANNOTATION_SENTINEL"));
    }

    private static MethodSignature methodSyntax(
            MethodId methodId,
            String source,
            String sql,
            SqlSource sqlSource,
            List<TypeReference> parameters,
            Optional<TypeReference> returnType,
            List<String> annotations) {
        return methodSyntax(methodId, source, sql, sqlSource, parameters, returnType, annotations, List.of());
    }

    private static MethodSignature methodSyntax(
            MethodId methodId,
            String source,
            String sql,
            SqlSource sqlSource,
            List<TypeReference> parameters,
            Optional<TypeReference> returnType,
            List<String> annotations,
            List<ResolvedTypeIdentity> bodyTypeReferences) {
        SyntaxRange range = range();
        return new MethodSignature(
                methodId.methodName(), methodId.parameterTypes(), annotations,
                sql, sqlSource, 1, 2, range, new SourceSlice(range, source), parameters, returnType, List.of(),
                List.of(), bodyTypeReferences);
    }

    private record BodyTypeSentinel(String sentinel, String className) {
    }

    private static TypeReference typeReference(
            String writtenType, String resolvedType, List<TypeReference> arguments) {
        return new TypeReference(writtenType, resolvedType, arguments, resolvedType.startsWith("com.example"));
    }

    private static SyntaxRange range() {
        return new SyntaxRange(
                new SyntaxPosition(0, 0),
                new SyntaxPosition(2, 0));
    }

    private static MethodId method(String packageName, String className, String methodName) {
        return new MethodId(REPO, "com.example." + packageName, className, methodName, List.of());
    }

    private static ReadPolicy readable() {
        return forbidding(new TypeId("other", "other", "Other"));
    }

    private static ReadPolicy forbidding(TypeId forbidden) {
        return new ReadPolicy() {
            @Override
            public EvidenceVisibility visibilityOfRepository(String repositoryId) {
                return EvidenceVisibility.READABLE;
            }

            @Override
            public EvidenceVisibility visibilityOf(TypeId typeId) {
                return forbidden.equals(typeId)
                        ? EvidenceVisibility.BUSINESS_READ_FORBIDDEN
                        : EvidenceVisibility.READABLE;
            }

            @Override
            public EvidenceVisibility visibilityOf(MethodId methodId) {
                return EvidenceVisibility.READABLE;
            }
        };
    }
}
