package com.java.semantic.query.application;

import com.java.semantic.model.codefact.CodeFactIdentity;
import com.java.semantic.model.codefact.CodeFactKind;
import com.java.semantic.model.codefact.CodeFactSearchQuery;
import com.java.semantic.model.codefact.CodeFactScope;
import com.java.semantic.model.index.ProjectionName;
import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.mongodb.client.MongoClients;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.bson.Document;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.mongodb.MongoDBContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A Query tool must reject a published generation from before its projection contract. */
@Tag("mongo-it")
class ToolProjectionEvolutionIT extends PublishedMongoITSupport {

    @Test
    void rejects_search_v1_with_a_typed_contract_failure_then_accepts_the_rebuilt_same_revision_v2() {
        try (MongoDBContainer container = new MongoDBContainer("mongo:8.0.4")) {
            container.start();
            MongoTemplate template = new MongoTemplate(MongoClients.create(container.getConnectionString()), "tool_projection_evolution");
            seedCurrent(template, "orders");
            template.getCollection("generation_manifests").updateOne(new Document("repoId", "orders").append("generationId", "g1"),
                    new Document("$set", new Document("projectionVersions", v1Versions())));
            CodeFactSearchService search = new CodeFactSearchService(template, selector(template, policy()), Duration.ofSeconds(2));
            CodeFactSearchQuery query = new CodeFactSearchQuery(new RepositoryId("orders"), new RepositoryRevision(REVISION), "payment",
                    java.util.Set.of(CodeFactKind.METHOD), Optional.empty(), 0, 20);

            assertThatThrownBy(() -> search.search(query)).isInstanceOf(IndexContractMismatchException.class)
                    .hasMessage("INDEX_CONTRACT_MISMATCH");

            CodeFactIdentity identity = methodIdentity("example.payment", "PaymentService", "findPayment",
                    "src/main/java/example/payment/PaymentService.java");
            seedG2SearchAndAuthoritativeMethod(template, identity);
            template.getCollection("generation_manifests").insertOne(new Document("repoId", "orders").append("sourceRevision", REVISION)
                    .append("generationId", "g2").append("identityDigest", "3".repeat(64)).append("writeState", "SEALED_VALID")
                    .append("schemaVersion", 1).append("projectionVersions", currentVersions()));
            template.getCollection("repositories").updateOne(new Document("repoId", "orders"), new Document("$set",
                    new Document("generationId", "g2").append("manifestDigest", "3".repeat(64))));

            assertThat(search.search(query).generation().generationId().value()).isEqualTo("g2");
            assertThat(search.search(query).facts()).extracting(summary -> summary.fact().identity().canonicalForm())
                    .contains(identity.canonicalForm());
            assertThat(selector(template, policy()).selectCodeFact("orders", REVISION, identity,
                    CurrentGenerationSelector.SEARCH).generationId().value()).isEqualTo("g2");
            assertThat(selector(template, policy()).currentRepository("orders").revision().value()).isEqualTo(REVISION);
        }
    }

    private static List<Document> v1Versions() {
        return List.of(new Document("name", ProjectionName.SOURCES.name()).append("version", 2),
                new Document("name", ProjectionName.SYMBOLS.name()).append("version", 2),
                new Document("name", ProjectionName.RELATIONS.name()).append("version", 2),
                new Document("name", ProjectionName.ENTRY_POINTS.name()).append("version", 2),
                new Document("name", ProjectionName.SEARCH.name()).append("version", 1));
    }

    private static List<Document> currentVersions() {
        return com.java.semantic.model.index.IndexSchemaContract.requiredProjectionVersions().entrySet().stream()
                .map(entry -> new Document("name", entry.getKey()).append("version", entry.getValue())).toList();
    }

    private static void seedG2SearchAndAuthoritativeMethod(MongoTemplate template, CodeFactIdentity identity) {
        com.java.semantic.model.index.SymbolDocument g1Method = seedMethod(template, identity, List.of());
        com.java.semantic.model.codefact.MethodTarget target = (com.java.semantic.model.codefact.MethodTarget) identity.canonicalIdentity();
        Document g1Document = new Document();
        template.getConverter().write(g1Method, g1Document);
        g1Document.put("repoId", "orders");
        g1Document.put("generationId", "g2");
        g1Document.put("symbolId", g1Method.fact().id().value());
        g1Document.put("canonical", identity.canonicalForm());
        g1Document.put("sourcePath", target.sourceFile());
        CodeFactScope scope = CodeFactScope.from(identity);
        g1Document.put("scopePackage", scope.packageName());
        g1Document.put("scopeClass", scope.className());
        g1Document.put("scopeMethod", scope.methodName().orElse(""));
        g1Document.put("scopeParameters", scope.parameterTypes());
        g1Document.put("scopePath", scope.sourcePath().orElse(""));
        template.getCollection("symbols").insertOne(g1Document);

        Document searchDocument = new Document("repoId", "orders").append("generationId", "g2")
                .append("factId", g1Method.fact().id().value()).append("kind", CodeFactKind.METHOD.name())
                .append("tokens", List.of("payment")).append("package", "example.payment")
                .append("authority", ProjectionName.SYMBOLS.name()).append("canonical", identity.canonicalForm())
                .append("scopePackage", scope.packageName()).append("scopeClass", scope.className())
                .append("scopeMethod", scope.methodName().orElse("")).append("scopeParameters", scope.parameterTypes())
                .append("scopePath", scope.sourcePath().orElse("")).append("sourcePath", target.sourceFile());
        template.getCollection("search").insertOne(searchDocument);
    }
}
