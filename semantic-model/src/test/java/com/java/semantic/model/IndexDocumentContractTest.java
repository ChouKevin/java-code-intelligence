package com.java.semantic.model;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.java.semantic.model.codefact.CodeFact;
import com.java.semantic.model.codefact.CodeFactId;
import com.java.semantic.model.codefact.CodeFactIdentity;
import com.java.semantic.model.codefact.CodeFactKind;
import com.java.semantic.model.codefact.EntryPointIdentity;
import com.java.semantic.model.codefact.EntryPointKind;
import com.java.semantic.model.codefact.EntryPointTrigger;
import com.java.semantic.model.codefact.ExternalTarget;
import com.java.semantic.model.codefact.JavaTypeIdentity;
import com.java.semantic.model.codefact.MethodTarget;
import com.java.semantic.model.codefact.RelationIdentity;
import com.java.semantic.model.codefact.RelationKind;
import com.java.semantic.model.codefact.RelationTarget;
import com.java.semantic.model.codefact.SourceRange;
import com.java.semantic.model.codefact.SourceTypeIdentity;
import com.java.semantic.model.codefact.SyntaxPosition;
import com.java.semantic.model.codefact.SyntaxRange;
import com.java.semantic.model.index.EntryPointDocument;
import com.java.semantic.model.index.GenerationFileDocument;
import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.index.GenerationManifestDocument;
import com.java.semantic.model.index.GenerationWriteState;
import com.java.semantic.model.index.IndexSchemaVersion;
import com.java.semantic.model.index.IndexCollections;
import com.java.semantic.model.index.IndexSchemaContract;
import com.java.semantic.model.index.ManifestDigest;
import com.java.semantic.model.index.ProjectionName;
import com.java.semantic.model.index.ProjectionRequirements;
import com.java.semantic.model.index.ProjectionVersion;
import com.java.semantic.model.index.RelationDocument;
import com.java.semantic.model.index.SearchDocument;
import com.java.semantic.model.index.SourceArtifactDocument;
import com.java.semantic.model.index.SourceArtifactId;
import com.java.semantic.model.index.SymbolDocument;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;

class IndexDocumentContractTest {

    @Test
    void exposes_the_required_framework_neutral_index_contract_types() {
        List<String> requiredTypes = List.of(
                "com.java.semantic.model.repository.RepositoryId",
                "com.java.semantic.model.repository.RepositoryRevision",
                "com.java.semantic.model.index.GenerationId",
                "com.java.semantic.model.index.GenerationManifestDocument",
                "com.java.semantic.model.codefact.CodeFact",
                "com.java.semantic.model.query.CurrentGeneration");

        for (String requiredType : requiredTypes) {
            assertDoesNotThrow(() -> Class.forName(requiredType));
        }
    }

    @Test
    void generation_and_repository_documents_expose_no_distributed_ownership_fields() {
        Set<String> manifestFields = java.util.Arrays.stream(GenerationManifestDocument.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<String> repositoryFields = java.util.Arrays.stream(com.java.semantic.model.index.RepositoryIndexDocument.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).collect(java.util.stream.Collectors.toUnmodifiableSet());

        assertTrue(java.util.Collections.disjoint(manifestFields,
                Set.of("ownerWorkerId", "fence", "sealUntil", "heartbeatAt")));
        assertTrue(java.util.Collections.disjoint(repositoryFields,
                Set.of("activeJobId", "activeWorkerId", "activeGenerationId", "claimUntil", "fence")));
    }

    @ParameterizedTest
    @MethodSource("invalidContractValues")
    void rejects_invalid_public_contract_values(String kind, Runnable construction) {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, construction::run);
        assertDoesNotThrow(exception::getMessage, kind);
    }

    static Stream<Arguments> invalidContractValues() {
        return Stream.of(
                Arguments.of("blank repository id", (Runnable) () -> new RepositoryId(" ")),
                Arguments.of("invalid repository revision", (Runnable) () -> new RepositoryRevision("A".repeat(40))),
                Arguments.of("unnormalized source path", (Runnable) () -> new SourceRange("../Order.java",
                        new SyntaxRange(new SyntaxPosition(0, 0), new SyntaxPosition(0, 0)))),
                Arguments.of("invalid source hash", (Runnable) () -> new SourceArtifactId("x".repeat(64))),
                Arguments.of("reverse source range", (Runnable) () -> new SyntaxRange(
                        new SyntaxPosition(2, 0), new SyntaxPosition(1, 0))),
                Arguments.of("empty projection requirements", (Runnable) () -> new ProjectionRequirements(Set.of())),
                Arguments.of("negative collection count", (Runnable) () -> manifest(Map.of("symbols", -1L))));
    }

    @Test
    void source_artifact_hash_and_offsets_are_content_derived_and_utf16_preserving() {
        SourceArtifactDocument artifact = SourceArtifactDocument.create("a\r\n\uD83D\uDE00\n");

        assertEquals(artifact.id().value(), artifact.contentHash());
        assertEquals(List.of(0, 3, 6), artifact.lineOffsets());
        assertEquals(6, artifact.utf8Content().length());
    }

    @Test
    void source_artifacts_are_immutable_values_with_a_useful_representation() {
        SourceArtifactDocument first = SourceArtifactDocument.create("class Order {}\n");
        SourceArtifactDocument second = SourceArtifactDocument.create("class Order {}\n");

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertThrows(UnsupportedOperationException.class, () -> first.lineOffsets().add(1));
        assertTrue(first.toString().contains(first.contentHash()));
    }

    @Test
    void rejects_a_forged_source_artifact_derivative() {
        assertEquals(0, SourceArtifactDocument.class.getConstructors().length);
    }

    @Test
    void code_fact_id_is_deterministic_and_scoped_to_repository_and_revision() {
        SourceTypeIdentity type = new SourceTypeIdentity(new JavaTypeIdentity("com.example", "Order"), "src/Order.java");
        CodeFactIdentity identity = new CodeFactIdentity(new RepositoryId("orders"), new RepositoryRevision("a".repeat(40)),
                CodeFactKind.TYPE, type);
        CodeFactId id = CodeFactId.from(identity);

        assertEquals(id, CodeFactId.from(identity));
        assertEquals(new CodeFact(id, identity), new CodeFact(id, identity));
    }

    @Test
    void exposes_only_the_five_reusable_projections() {
        assertEquals(Set.of(ProjectionName.SOURCES, ProjectionName.SYMBOLS, ProjectionName.RELATIONS,
                ProjectionName.ENTRY_POINTS, ProjectionName.SEARCH), Set.of(ProjectionName.values()));
        assertEquals(List.of(IndexCollections.GENERATION_FILES, IndexCollections.SOURCE_ARTIFACTS),
                IndexCollections.PROJECTION_COLLECTIONS.get(ProjectionName.SOURCES));
    }

    @Test
    void requires_the_canonical_relations_projection_version() {
        assertEquals(2, IndexSchemaContract.requiredProjectionVersions().get(ProjectionName.RELATIONS.name()));
    }

    @Test
    void schema_index_keys_preserve_compound_order_without_exposing_mutable_contract_state() {
        IndexSchemaContract.IndexSpec index = IndexSchemaContract.collections().stream()
                .flatMap(collection -> collection.indexes().stream())
                .filter(candidate -> candidate.name().equals("generation_file_unique"))
                .findFirst()
                .orElseThrow();
        LinkedHashMap<String, Integer> callerCopy = index.keys();
        callerCopy.clear();

        assertEquals(List.of("repoId", "generationId", "sourcePath"), List.copyOf(index.keys().keySet()));
    }

    @Test
    void schema_exposes_the_target_relation_lookup_in_deterministic_order() {
        IndexSchemaContract.IndexSpec index = IndexSchemaContract.collections().stream()
                .filter(collection -> collection.name().equals("relations"))
                .flatMap(collection -> collection.indexes().stream())
                .filter(candidate -> candidate.name().equals("relation_target_kind_source"))
                .findFirst()
                .orElseThrow();

        assertEquals(List.of("repoId", "generationId", "target", "kind", "from", "sourcePath", "relationId"),
                List.copyOf(index.keys().keySet()));
    }

    @Test
    void rejects_search_documents_with_mismatched_repository_kind_fact_id_or_projection() {
        CodeFactIdentity identity = symbolIdentity("orders", "a", CodeFactKind.TYPE);
        CodeFactId factId = CodeFactId.from(identity);

        assertThrows(IllegalArgumentException.class, () -> searchDocument(new RepositoryId("billing"), factId,
                CodeFactKind.TYPE, ProjectionName.SYMBOLS, identity));
        assertThrows(IllegalArgumentException.class, () -> searchDocument(new RepositoryId("orders"), factId,
                CodeFactKind.METHOD, ProjectionName.SYMBOLS, identity));
        assertThrows(IllegalArgumentException.class, () -> searchDocument(new RepositoryId("orders"),
                CodeFactId.from(symbolIdentity("orders", "b", CodeFactKind.TYPE)), CodeFactKind.TYPE,
                ProjectionName.SYMBOLS, identity));
        assertThrows(IllegalArgumentException.class, () -> searchDocument(new RepositoryId("orders"), factId,
                CodeFactKind.TYPE, ProjectionName.RELATIONS, identity));
    }

    @Test
    void rejects_symbol_documents_with_a_mismatched_repository_or_non_symbol_kind() {
        CodeFact typeFact = fact(symbolIdentity("orders", "a", CodeFactKind.TYPE));
        CodeFact routeFact = entryPointFact(EntryPointKind.HTTP, httpTrigger());

        assertThrows(IllegalArgumentException.class, () -> symbolDocument(new RepositoryId("billing"), typeFact,
                CodeFactKind.TYPE));
        assertThrows(IllegalArgumentException.class, () -> symbolDocument(new RepositoryId("orders"), routeFact,
                CodeFactKind.API_ROUTE));
    }

    @Test
    void relation_identity_is_range_sensitive_deterministic_and_typed() {
        CodeFactIdentity from = symbolIdentity("orders", "a", CodeFactKind.METHOD);
        CodeFactIdentity target = symbolIdentity("orders", "a", CodeFactKind.TYPE);
        RelationTarget relationTarget = new RelationTarget.Internal(target);
        RelationIdentity first = new RelationIdentity(from, RelationKind.CALLS, relationTarget, range(4));
        RelationIdentity second = new RelationIdentity(from, RelationKind.CALLS, relationTarget, range(5));

        CodeFactId firstId = CodeFactId.from(new CodeFactIdentity(repository("orders"), revision("a"),
                CodeFactKind.TYPE_USAGE, first));
        CodeFactId secondId = CodeFactId.from(new CodeFactIdentity(repository("orders"), revision("a"),
                CodeFactKind.TYPE_USAGE, second));

        assertEquals(firstId, CodeFactId.from(new CodeFactIdentity(repository("orders"), revision("a"),
                CodeFactKind.TYPE_USAGE, first)));
        assertNotEquals(firstId, secondId);
        assertNotEquals(
                new RelationTarget.External(new ExternalTarget.Endpoint("GET /orders", "id")).canonicalForm(),
                new RelationTarget.External(new ExternalTarget.Endpoint("GET", "/orders id")).canonicalForm());
        assertThrows(NullPointerException.class, () -> new RelationTarget.Internal(null));
    }

    @Test
    void rejects_relation_documents_with_inconsistent_identity_or_scope() {
        CodeFactIdentity ordersFrom = symbolIdentity("orders", "a", CodeFactKind.METHOD);
        CodeFactIdentity ordersTarget = symbolIdentity("orders", "a", CodeFactKind.TYPE);
        RelationTarget.Internal internalTarget = new RelationTarget.Internal(ordersTarget);
        RelationIdentity relationIdentity = new RelationIdentity(ordersFrom, RelationKind.CALLS, internalTarget, range(4));
        CodeFact validFact = relationFact("orders", "a", CodeFactKind.TYPE_USAGE, relationIdentity);

        assertThrows(IllegalArgumentException.class, () -> relationDocument(new RepositoryId("billing"), validFact,
                RelationKind.CALLS, ordersFrom, internalTarget, range(4)));
        assertThrows(IllegalArgumentException.class, () -> relationDocument(repository("orders"),
                relationFact("billing", "a", CodeFactKind.TYPE_USAGE, relationIdentity), RelationKind.CALLS,
                ordersFrom, internalTarget, range(4)));
        CodeFactIdentity billingFrom = symbolIdentity("billing", "a", CodeFactKind.METHOD);
        RelationIdentity billingFromIdentity = new RelationIdentity(billingFrom, RelationKind.CALLS, internalTarget, range(4));
        assertThrows(IllegalArgumentException.class, () -> relationDocument(repository("orders"),
                relationFact("orders", "a", CodeFactKind.TYPE_USAGE, billingFromIdentity), RelationKind.CALLS,
                billingFrom, internalTarget, range(4)));
        CodeFactIdentity billingTarget = symbolIdentity("billing", "a", CodeFactKind.TYPE);
        RelationTarget.Internal foreignTarget = new RelationTarget.Internal(billingTarget);
        RelationIdentity foreignTargetIdentity = new RelationIdentity(ordersFrom, RelationKind.CALLS, foreignTarget, range(4));
        assertThrows(IllegalArgumentException.class, () -> relationDocument(repository("orders"),
                relationFact("orders", "a", CodeFactKind.TYPE_USAGE, foreignTargetIdentity), RelationKind.CALLS,
                ordersFrom, foreignTarget, range(4)));
        RelationIdentity wrongRangeIdentity = new RelationIdentity(ordersFrom, RelationKind.CALLS, internalTarget, range(5));
        assertThrows(IllegalArgumentException.class, () -> relationDocument(repository("orders"),
                relationFact("orders", "a", CodeFactKind.TYPE_USAGE, wrongRangeIdentity), RelationKind.CALLS,
                ordersFrom, internalTarget, range(4)));
        assertThrows(IllegalArgumentException.class, () -> relationDocument(repository("orders"),
                relationFact("orders", "a", CodeFactKind.METHOD, relationIdentity), RelationKind.CALLS,
                ordersFrom, internalTarget, range(4)));
    }

    @Test
    void rejects_relation_documents_with_misaligned_revisions() {
        CodeFactIdentity from = symbolIdentity("orders", "b", CodeFactKind.METHOD);
        CodeFactIdentity target = symbolIdentity("orders", "a", CodeFactKind.TYPE);
        RelationTarget.Internal relationTarget = new RelationTarget.Internal(target);
        RelationIdentity identity = new RelationIdentity(from, RelationKind.CALLS, relationTarget, range(4));
        CodeFact fact = relationFact("orders", "a", CodeFactKind.TYPE_USAGE, identity);

        assertThrows(IllegalArgumentException.class, () -> relationDocument(repository("orders"), fact,
                RelationKind.CALLS, from, relationTarget, range(4)));

        CodeFactIdentity alignedFrom = symbolIdentity("orders", "a", CodeFactKind.METHOD);
        CodeFactIdentity staleTarget = symbolIdentity("orders", "b", CodeFactKind.TYPE);
        RelationTarget.Internal staleRelationTarget = new RelationTarget.Internal(staleTarget);
        RelationIdentity staleTargetIdentity = new RelationIdentity(alignedFrom, RelationKind.CALLS,
                staleRelationTarget, range(4));
        assertThrows(IllegalArgumentException.class, () -> relationDocument(repository("orders"),
                relationFact("orders", "a", CodeFactKind.TYPE_USAGE, staleTargetIdentity), RelationKind.CALLS,
                alignedFrom, staleRelationTarget, range(4)));
    }

    @Test
    void entry_point_documents_require_a_matching_fact_identity_kind_and_trigger_shape() {
        MethodTarget method = method();
        EntryPointTrigger http = httpTrigger();
        CodeFact validFact = entryPointFact(EntryPointKind.HTTP, http);

        assertDoesNotThrow(() -> entryPointDocument(repository("orders"), validFact, EntryPointKind.HTTP, method, http));
        assertThrows(IllegalArgumentException.class, () -> entryPointDocument(repository("billing"), validFact,
                EntryPointKind.HTTP, method, http));
        EntryPointTrigger differentHttp = new EntryPointTrigger(Optional.of("GET"), Optional.of("/orders/{id}"),
                Optional.empty(), Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> entryPointDocument(repository("orders"), validFact,
                EntryPointKind.HTTP, method, differentHttp));
        assertThrows(IllegalArgumentException.class, () -> entryPointDocument(repository("orders"),
                fact(new CodeFactIdentity(repository("orders"), revision("a"), CodeFactKind.MQ_DESTINATION,
                        entryPointIdentity(EntryPointKind.HTTP, http))), EntryPointKind.HTTP, method, http));
        EntryPointTrigger partialHttp = new EntryPointTrigger(Optional.of("GET"), Optional.empty(), Optional.empty(),
                Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> entryPointDocument(repository("orders"),
                entryPointFact(EntryPointKind.HTTP, partialHttp), EntryPointKind.HTTP, method, partialHttp));
        EntryPointTrigger mixedHttp = new EntryPointTrigger(Optional.of("GET"), Optional.of("/orders"),
                Optional.of(new ExternalTarget.Destination("kafka", "orders")), Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> entryPointDocument(repository("orders"),
                entryPointFact(EntryPointKind.HTTP, mixedHttp), EntryPointKind.HTTP, method, mixedHttp));
        EntryPointTrigger empty = new EntryPointTrigger(Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> entryPointDocument(repository("orders"),
                entryPointFact(EntryPointKind.MQ, empty), EntryPointKind.MQ, method, empty));
        EntryPointTrigger mq = new EntryPointTrigger(Optional.empty(), Optional.empty(),
                Optional.of(new ExternalTarget.Destination("kafka", "orders")), Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> entryPointDocument(repository("orders"),
                entryPointFact(EntryPointKind.HTTP, mq), EntryPointKind.HTTP, method, mq));
    }

    private static SearchDocument searchDocument(
            RepositoryId repositoryId,
            CodeFactId factId,
            CodeFactKind kind,
            ProjectionName projection,
            CodeFactIdentity identity) {
        return new SearchDocument(repositoryId, new GenerationId("generation-1"), factId, kind, List.of("order"),
                Optional.of("com.example"), projection, identity);
    }

    private static SymbolDocument symbolDocument(RepositoryId repositoryId, CodeFact fact, CodeFactKind kind) {
        return new SymbolDocument(repositoryId, new GenerationId("generation-1"), fact, kind, "com.example.Order",
                "method", "method()", new com.java.semantic.model.codefact.DeclaredType("void"), Set.of(), List.of(),
                new SourceArtifactId("a".repeat(64)), range(4));
    }

    private static RelationDocument relationDocument(
            RepositoryId repositoryId,
            CodeFact fact,
            RelationKind kind,
            CodeFactIdentity from,
            RelationTarget target,
            SourceRange range) {
        return new RelationDocument(repositoryId, new GenerationId("generation-1"), fact, kind, from, target,
                new SourceArtifactId("a".repeat(64)), range);
    }

    private static EntryPointDocument entryPointDocument(
            RepositoryId repositoryId,
            CodeFact fact,
            EntryPointKind kind,
            MethodTarget method,
            EntryPointTrigger trigger) {
        return new EntryPointDocument(repositoryId, new GenerationId("generation-1"), fact, kind, method, trigger,
                range(4));
    }

    private static CodeFact relationFact(
            String repository,
            String revision,
            CodeFactKind kind,
            RelationIdentity identity) {
        return fact(new CodeFactIdentity(repository(repository), revision(revision), kind, identity));
    }

    private static CodeFact fact(CodeFactIdentity identity) {
        return new CodeFact(CodeFactId.from(identity), identity);
    }

    private static CodeFact entryPointFact(EntryPointKind kind, EntryPointTrigger trigger) {
        return fact(new CodeFactIdentity(repository("orders"), revision("a"), expectedEntryPointFactKind(kind),
                entryPointIdentity(kind, trigger)));
    }

    private static CodeFactKind expectedEntryPointFactKind(EntryPointKind kind) {
        return switch (kind) {
            case HTTP -> CodeFactKind.API_ROUTE;
            case MQ -> CodeFactKind.MQ_DESTINATION;
            case SCHEDULE -> CodeFactKind.SCHEDULE;
        };
    }

    private static CodeFactIdentity symbolIdentity(String repository, String revision, CodeFactKind kind) {
        return new CodeFactIdentity(repository(repository), revision(revision), kind,
                new SourceTypeIdentity(new JavaTypeIdentity("com.example", "Order"), "src/Order.java"));
    }

    private static EntryPointIdentity entryPointIdentity(EntryPointKind kind, EntryPointTrigger trigger) {
        return new EntryPointIdentity(kind, method(), trigger);
    }

    private static MethodTarget method() {
        return new MethodTarget(new SourceTypeIdentity(new JavaTypeIdentity("com.example", "Order"), "src/Order.java"),
                "handle", List.of("java.lang.String"));
    }

    private static EntryPointTrigger httpTrigger() {
        return new EntryPointTrigger(Optional.of("GET"), Optional.of("/orders"), Optional.empty(), Optional.empty());
    }

    private static SourceRange range(int character) {
        return new SourceRange("src/Order.java", new SyntaxRange(new SyntaxPosition(0, character),
                new SyntaxPosition(0, character + 1)));
    }

    private static RepositoryId repository(String value) {
        return new RepositoryId(value);
    }

    private static RepositoryRevision revision(String hexadecimalCharacter) {
        return new RepositoryRevision(hexadecimalCharacter.repeat(40));
    }

    private static GenerationManifestDocument manifest(Map<String, Long> counts) {
        return new GenerationManifestDocument(
                new RepositoryId("orders"),
                new RepositoryRevision("a".repeat(40)),
                new GenerationId("generation-1"),
                "job-1",
                GenerationWriteState.SEALED_VALID,
                1,
                new IndexSchemaVersion(1),
                List.of(new ProjectionVersion(ProjectionName.SYMBOLS, 1)),
                counts,
                new ManifestDigest("b".repeat(64)),
                Optional.of("valid"),
                Optional.of(Instant.parse("2026-08-23T00:00:00Z")));
    }
}
