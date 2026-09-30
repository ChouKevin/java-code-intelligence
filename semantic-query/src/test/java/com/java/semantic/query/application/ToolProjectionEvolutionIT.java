package com.java.semantic.query.application;

import com.java.semantic.model.codefact.CodeFactIdentity;
import com.java.semantic.model.codefact.CodeFactKind;
import com.java.semantic.model.codefact.CodeFactSearchQuery;
import com.java.semantic.model.codefact.CodeFactScope;
import com.java.semantic.model.index.ProjectionName;
import com.java.semantic.model.index.IndexSchemaContract;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.query.SelectedGeneration;
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
    void rejects_search_v1_with_a_typed_contract_failure_then_accepts_the_rebuilt_same_revision_current_contract() {
        try (MongoDBContainer container = new MongoDBContainer("mongo:8.0.4")) {
            container.start();
            MongoTemplate template = new MongoTemplate(MongoClients.create(container.getConnectionString()), "tool_projection_evolution");
            seedCurrent(template, "orders");
            template.getCollection("generation_manifests").updateOne(new Document("repoId", "orders").append("generationId", "g1"),
                    new Document("$set", new Document("projectionVersions", v1Versions())));
            CurrentGenerationSelector selector = selector(template, policy());
            SelectedGenerationGuard guard = guard(template, policy());
            CodeFactSearchService search = new CodeFactSearchService(template, guard, Duration.ofSeconds(2));
            CodeFactSearchQuery query = new CodeFactSearchQuery(new RepositoryId("orders"), new RepositoryRevision(REVISION), "payment",
                    java.util.Set.of(CodeFactKind.METHOD), Optional.empty(), 0, 20);

            assertThatThrownBy(() -> search.search(selector.select("orders", REVISION,
                    CodeFactReadService.requirementsForSearchKinds(query.kinds())), query)).isInstanceOf(IndexContractMismatchException.class)
                    .hasMessage("INDEX_CONTRACT_MISMATCH");

            CodeFactIdentity identity = methodIdentity("example.payment", "PaymentService", "findPayment",
                    "src/main/java/example/payment/PaymentService.java");
            seedG2SearchAndAuthoritativeMethod(template, identity);
            Document g1 = template.getCollection("generation_manifests").find(new Document("repoId", "orders")
                    .append("generationId", "g1")).first();
            String snapshotId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2";
            Document snapshot = new Document(template.getCollection("git_evidence_manifests")
                    .find(new Document("repoId", "orders").append("evidenceId", SOURCE_SNAPSHOT)).first());
            snapshot.remove("_id");
            snapshot.put("evidenceId", snapshotId);
            snapshot.put("sourceGenerationId", "g2");
            template.getCollection("git_evidence_manifests").insertOne(snapshot);
            Document membership = new Document(g1.get("sourceSnapshot", Document.class));
            membership.put("snapshotId", snapshotId);
            template.getCollection("generation_manifests").insertOne(new Document("repoId", "orders").append("sourceRevision", REVISION)
                    .append("generationId", "g2").append("identityDigest", "3".repeat(64)).append("writeState", "SEALED_VALID")
                    .append("schemaVersion", IndexSchemaContract.SCHEMA_VERSION).append("projectionVersions", currentVersions())
                    .append("sourceSnapshot", membership).append("sourcePolicy", g1.get("sourcePolicy"))
                    .append("projectGuide", g1.get("projectGuide")).append("coverage", g1.get("coverage"))
                    .append("structure", g1.get("structure")));
            template.getCollection("repositories").updateOne(new Document("repoId", "orders"), new Document("$set",
                    new Document("currentPointer.generationId", "g2").append("currentPointer.manifestDigest", "3".repeat(64))
                            .append("currentPointer.committedJobId", "job-g2").append("currentPointer.publishedAt", new java.util.Date())));
            SelectedGeneration context = selector.select("orders", REVISION,
                    CodeFactReadService.requirementsForSearchKinds(query.kinds()));
            assertThat(search.search(context, query).generation().generationId().value()).isEqualTo("g2");
            assertThat(search.search(context, query).facts()).extracting(summary -> summary.fact().identity().canonicalForm())
                    .contains(identity.canonicalForm());
            assertThat(selector.selectCodeFact("orders", REVISION, identity,
                    SelectedGenerationGuard.SEARCH).generationId().value()).isEqualTo("g2");
            assertThat(selector(template, policy()).currentRepository("orders").revision().value()).isEqualTo(REVISION);
        }
    }

    private static List<Document> v1Versions() {
        return IndexSchemaContract.requiredProjectionVersions().entrySet().stream()
                .map(entry -> new Document("name", entry.getKey()).append("version",
                        ProjectionName.SEARCH.name().equals(entry.getKey()) ? 1 : entry.getValue()))
                .toList();
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
                .append("displayName", target.methodName())
                .append("signature", target.methodName() + "(" + String.join(", ", target.parameterTypes()) + ")")
                .append("scopePackage", scope.packageName()).append("scopeClass", scope.className())
                .append("scopeMethod", scope.methodName().orElse("")).append("scopeParameters", scope.parameterTypes())
                .append("scopePath", scope.sourcePath().orElse("")).append("sourcePath", target.sourceFile());
        template.getCollection("search").insertOne(searchDocument);
    }
}
