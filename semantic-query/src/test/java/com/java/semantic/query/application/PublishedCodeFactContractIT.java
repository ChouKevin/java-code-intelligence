package com.java.semantic.query.application;

import com.java.semantic.model.codefact.CodeFactIdentity;
import com.java.semantic.model.codefact.CodeFactId;
import com.java.semantic.model.codefact.CodeFactKind;
import com.java.semantic.model.codefact.CodeFactTokenizer;
import com.java.semantic.model.codefact.MapperStatementKind;
import com.java.semantic.model.index.IndexSchemaContract;
import com.java.semantic.model.index.ProjectionName;
import com.java.semantic.model.index.ProjectionRequirements;
import com.java.semantic.model.index.SourceArtifactId;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.data.mongodb.core.MongoTemplate;
import static com.java.semantic.query.application.SemanticQueryContract.*;
import static org.assertj.core.api.Assertions.*;

@Tag("mongo-it")
class PublishedCodeFactContractIT extends PublishedMongoITSupport {
    private static final String PATH = "src/main/java/example/Payment.java";

    @Test
    void exact_then_name_prefix_then_all_token_prefix_pages_do_not_duplicate_or_skip() {
        try (PublishedMongoLifecycle lifecycle = PublishedMongoLifecycle.start(); PublishedMongoLifecycle.Invocation invocation = lifecycle.openInvocation()) {
            MongoTemplate template = invocation.template();
            seedCurrent(template, "orders");
            seedSource(template, PATH, "class Payment {}\n");
            for (String name : List.of("findPayment", "findPaymentHistory", "lookupPayment")) {
                CodeFactIdentity identity = methodIdentity("example", "Payment", name, PATH);
                seedMethod(template, identity, List.of());
                seedSearch(template, identity, "SYMBOLS", name.equals("lookupPayment") ? List.of("find", "payment") : CodeFactTokenizer.tokenize(name));
            }
            SelectedSemanticQueryService service = semantic(template, policy());
            SearchCodeRequest request = search("findPayment", Optional.empty(), 1);
            ReadContextSelector.AdmittedContext context = admitted(template, policy(), service.searchRequirements(request));
            FactCollection exact = service.searchCode(context, request);
            assertThat(exact.items()).extracting(CompactFact::displayName).containsExactly("findPayment");
            FactCollection prefix = service.searchCode(context, search("findPayment", exact.page().nextCursor(), 1));
            assertThat(prefix.items()).extracting(CompactFact::displayName).containsExactly("findPaymentHistory");
            FactCollection token = service.searchCode(context, search("findPayment", prefix.page().nextCursor(), 1));
            assertThat(token.items()).extracting(CompactFact::displayName).containsExactly("lookupPayment");
            assertThat(token.page().hasMore()).isFalse();
            assertThatThrownBy(() -> service.searchCode(context, search("find", exact.page().nextCursor(), 1))).isInstanceOf(IllegalArgumentException.class);
            SearchCodeRequest wrongPath = new SearchCodeRequest(request.context(), request.query(), request.kinds(), Optional.empty(), Optional.of(PATH + ".other"), new PageRequest(Optional.empty(), 1));
            assertThat(service.searchCode(context, wrongPath).items()).isEmpty();
            SearchCodeRequest signature = search("findPayment(example.events.VideoReady)", Optional.empty(), 20);
            assertThat(service.searchCode(context, signature).items()).extracting(CompactFact::displayName).containsExactly("findPayment");
        }
    }

    @Test
    void metadata_only_batch_resolution_rejects_search_scope_artifact_and_snapshot_corruption() {
        try (PublishedMongoLifecycle lifecycle = PublishedMongoLifecycle.start(); PublishedMongoLifecycle.Invocation invocation = lifecycle.openInvocation()) {
            MongoTemplate template = invocation.template();
            seedCurrent(template, "orders");
            seedSource(template, PATH, "class Payment {}\n");
            CodeFactIdentity identity = methodIdentity("example", "Payment", "charge", PATH);
            seedMethod(template, identity, List.of());
            seedSearch(template, identity, "SYMBOLS", List.of("charge"));
            CodeFactReadService reader = new CodeFactReadService(template, guard(template, policy()), Duration.ofSeconds(2));
            SelectedGenerationGuard.SourceContext source = admitted(template, policy(), SelectedGenerationGuard.SEARCH_WITH_SOURCES).source();
            template.getCollection("source_artifacts").deleteMany(new Document());
            assertThat(reader.getAllByIdentity(source, Set.of(identity)).get(identity).fact().identity()).isEqualTo(identity);
            assertThat(reader.get(source, CodeFactId.from(identity)).fact().identity()).isEqualTo(identity);
            template.getCollection("search").updateOne(new Document("factId", CodeFactId.from(identity).value()), new Document("$set", new Document("displayName", "forged")));
            assertThatThrownBy(() -> reader.get(source, CodeFactId.from(identity))).isInstanceOf(IndexContractMismatchException.class);
            template.getCollection("search").updateOne(new Document("factId", CodeFactId.from(identity).value()), new Document("$set", new Document("displayName", "charge")));
            template.getCollection("git_snapshot_files").updateOne(new Document("path", PATH), new Document("$set", new Document("checksum", "f".repeat(64))));
            assertThatThrownBy(() -> reader.getAllByIdentity(source, Set.of(identity))).isInstanceOf(IndexContractMismatchException.class);
        }
    }

    @Test
    void method_reads_require_only_actual_authority_and_source_projections() {
        try (PublishedMongoLifecycle lifecycle = PublishedMongoLifecycle.start(); PublishedMongoLifecycle.Invocation invocation = lifecycle.openInvocation()) {
            MongoTemplate template = invocation.template(); seedCurrent(template, "orders"); seedSource(template, PATH, "class Payment {}\n");
            CodeFactIdentity identity = methodIdentity("example", "Payment", "charge", PATH); seedMethod(template, identity, List.of()); seedSearch(template, identity, "SYMBOLS", List.of("charge"));
            template.getCollection("generation_manifests").updateOne(new Document("generationId", "g1"), new Document("$set", new Document("projectionVersions",
                    IndexSchemaContract.requiredProjectionVersions().entrySet().stream().map(entry -> new Document("name", entry.getKey()).append("version",
                            Set.of("RELATIONS", "ENTRY_POINTS").contains(entry.getKey()) ? 1 : entry.getValue())).toList())));
            SelectedSemanticQueryService service = semantic(template, policy()); SearchCodeRequest request = search("charge", Optional.empty(), 20);
            assertThat(service.searchCode(admitted(template, policy(), service.searchRequirements(request)), request).items()).extracting(CompactFact::displayName).containsExactly("charge");
            SelectedGenerationGuard.SourceContext source = admitted(template, policy(), new ProjectionRequirements(Set.of(ProjectionName.SOURCES))).source();
            CodeFactReadService reader = new CodeFactReadService(template, guard(template, policy()), Duration.ofSeconds(2));
            assertThat(reader.getAllByIdentity(source, Set.of(identity))).containsKey(identity);
            template.getCollection("symbols").deleteOne(new Document("symbolId", CodeFactId.from(identity).value()));
            assertThatThrownBy(() -> reader.getAllByIdentity(source, Set.of(identity))).isInstanceOf(CodeFactNotFoundException.class);
        }
    }

    @Test
    void mapper_authority_retains_select_update_annotation_and_rejects_invalid_payloads() {
        try (PublishedMongoLifecycle lifecycle = PublishedMongoLifecycle.start(); PublishedMongoLifecycle.Invocation invocation = lifecycle.openInvocation()) {
            MongoTemplate template = invocation.template(); seedCurrent(template, "orders");
            for (MapperStatementKind kind : List.of(MapperStatementKind.SELECT, MapperStatementKind.UPDATE, MapperStatementKind.ANNOTATION)) {
                String path = kind == MapperStatementKind.ANNOTATION ? "src/main/java/example/Mapper.java" : "src/main/resources/mapper/" + kind + ".xml";
                SourceArtifactId artifact = seedSource(template, path, kind == MapperStatementKind.ANNOTATION ? "interface Mapper {}" : "<mapper/>").id();
                CodeFactIdentity identity = seedMapper(template, path, artifact, kind); seedSearch(template, identity, "SYMBOLS", List.of("find"));
                CodeFactReadService reader = new CodeFactReadService(template, guard(template, policy()), Duration.ofSeconds(2));
                SelectedGenerationGuard.SourceContext source = admitted(template, policy(), SelectedGenerationGuard.SEARCH_WITH_SOURCES).source();
                assertThat(reader.get(source, CodeFactId.from(identity)).mapperStatementKind()).contains(kind);
                if (kind == MapperStatementKind.SELECT) {
                    for (Object malformed : List.of("", "UPSERT", new Document("value", "SELECT"))) {
                        template.getCollection("symbols").updateOne(new Document("symbolId", CodeFactId.from(identity).value()),
                                new Document("$set", new Document("mapperStatementKind", malformed)));
                        assertThatThrownBy(() -> reader.get(source, CodeFactId.from(identity))).isInstanceOf(IndexContractMismatchException.class);
                    }
                }
                template.getCollection("symbols").updateOne(new Document("symbolId", CodeFactId.from(identity).value()), new Document("$unset", new Document("mapperStatementKind", "")));
                assertThatThrownBy(() -> reader.get(source, CodeFactId.from(identity))).isInstanceOf(IndexContractMismatchException.class);
            }
        }
    }

    @Test
    void nonmapper_declaration_rejects_mapper_operation_payload() {
        try (PublishedMongoLifecycle lifecycle = PublishedMongoLifecycle.start(); PublishedMongoLifecycle.Invocation invocation = lifecycle.openInvocation()) {
            MongoTemplate template = invocation.template(); seedCurrent(template, "orders"); seedSource(template, PATH, "class Payment {}\n");
            CodeFactIdentity identity = methodIdentity("example", "Payment", "charge", PATH); seedMethod(template, identity, List.of());
            CodeFactReadService reader = new CodeFactReadService(template, guard(template, policy()), Duration.ofSeconds(2));
            SelectedGenerationGuard.SourceContext source = admitted(template, policy(), SelectedGenerationGuard.SOURCES).source();
            template.getCollection("symbols").updateOne(new Document("symbolId", CodeFactId.from(identity).value()),
                    new Document("$set", new Document("mapperStatementKind", "UPDATE")));
            assertThatThrownBy(() -> reader.getAllByIdentity(source, Set.of(identity))).isInstanceOf(IndexContractMismatchException.class);
        }
    }

    @Test
    void small_semantic_page_batches_authority_and_membership_without_body_or_manifest_reads() {
        try (PublishedMongoLifecycle lifecycle = PublishedMongoLifecycle.start(); PublishedMongoLifecycle.Invocation invocation = lifecycle.openInvocation()) {
            MongoTemplate template = invocation.template(); seedCurrent(template, "orders"); seedSource(template, PATH, "class Payment {}\n");
            for (int index = 0; index < 25; index++) {
                String name = "findPayment" + String.format(java.util.Locale.ROOT, "%02d", index);
                CodeFactIdentity identity = methodIdentity("example", "Payment", name, PATH);
                seedMethod(template, identity, List.of()); seedSearch(template, identity, "SYMBOLS", List.of("find", "payment"));
            }
            java.util.concurrent.CopyOnWriteArrayList<org.bson.BsonDocument> commands = new java.util.concurrent.CopyOnWriteArrayList<>();
            com.mongodb.event.CommandListener listener = new com.mongodb.event.CommandListener() {
                @Override public void commandStarted(com.mongodb.event.CommandStartedEvent event) {
                    if (event.getCommandName().equals("find")) commands.add(event.getCommand().clone());
                }
            };
            com.mongodb.MongoClientSettings settings = com.mongodb.MongoClientSettings.builder()
                    .applyConnectionString(new com.mongodb.ConnectionString(lifecycle.connectionString())).addCommandListener(listener).build();
            try (com.mongodb.client.MongoClient client = com.mongodb.client.MongoClients.create(settings)) {
                MongoTemplate observed = new MongoTemplate(client, invocation.databaseName());
                SelectedSemanticQueryService service = semantic(observed, policy());
                SearchCodeRequest request = search("findPayment", Optional.empty(), 20);
                ReadContextSelector.AdmittedContext context = admitted(observed, policy(), service.searchRequirements(request));
                commands.clear();
                FactCollection result = service.searchCode(context, request);
                assertThat(result.items()).extracting(CompactFact::displayName).containsExactly(
                        "findPayment00", "findPayment01", "findPayment02", "findPayment03", "findPayment04",
                        "findPayment05", "findPayment06", "findPayment07", "findPayment08", "findPayment09",
                        "findPayment10", "findPayment11", "findPayment12", "findPayment13", "findPayment14",
                        "findPayment15", "findPayment16", "findPayment17", "findPayment18", "findPayment19");
                assertThat(result.page().hasMore()).isTrue();
                assertThat(commands).noneSatisfy(command -> assertThat(command.getString("find").getValue())
                        .isIn("generation_manifests", "git_evidence_manifests", "source_artifacts", "git_snapshot_chunks"));
                assertThat(commands.stream().filter(command -> command.getString("find").getValue().equals("symbols")).toList()).hasSize(1);
                assertThat(commands.stream().filter(command -> command.getString("find").getValue().equals("generation_files")).toList()).hasSize(1);
                assertThat(commands.stream().filter(command -> command.getString("find").getValue().equals("git_snapshot_files")).toList()).hasSize(1);
                for (org.bson.BsonDocument command : commands) assertThat(command.getNumber("limit").longValue()).isBetween(1L, 100L);
            }
        }
    }

    private static SearchCodeRequest search(String query, Optional<String> cursor, int limit) {
        return new SearchCodeRequest(ReadContext.current("orders", REVISION), query, Set.of(CodeFactKind.METHOD), Optional.empty(), Optional.of(PATH), new PageRequest(cursor, limit));
    }
}
