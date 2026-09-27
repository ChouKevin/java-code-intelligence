package com.java.semantic.query.application;

import com.java.semantic.model.codefact.CodeFact;
import com.java.semantic.model.codefact.CodeFactId;
import com.java.semantic.model.codefact.CodeFactIdentity;
import com.java.semantic.model.codefact.CodeFactKind;
import com.java.semantic.model.codefact.CodeFactScope;
import com.java.semantic.model.codefact.CodeFactSearchQuery;
import com.java.semantic.model.codefact.DeclaredType;
import com.java.semantic.model.codefact.JavaTypeIdentity;
import com.java.semantic.model.codefact.MethodTarget;
import com.java.semantic.model.codefact.SourceRange;
import com.java.semantic.model.codefact.SourceTypeIdentity;
import com.java.semantic.model.codefact.SyntaxPosition;
import com.java.semantic.model.codefact.SyntaxRange;
import com.java.semantic.model.index.GenerationFileDocument;
import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.index.IndexSchemaContract;
import com.java.semantic.model.index.SourceArtifactDocument;
import com.java.semantic.model.index.SourceIndexScope;
import com.java.semantic.model.index.SymbolDocument;
import com.java.semantic.model.query.SelectedGeneration;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.mongodb.client.MongoClients;
import com.mongodb.client.model.ReplaceOptions;
import org.bson.Document;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("mongo-it")
class SelectedGenerationReadContractIT extends PublishedMongoITSupport {
    @Test
    void keeps_the_complete_search_to_source_operation_pinned_after_current_advances() {
        try (MongoDBContainer container = new MongoDBContainer(DockerImageName.parse("mongo:8.0.4"))) {
            container.start();
            MongoTemplate template = new MongoTemplate(MongoClients.create(container.getConnectionString()), "selected_generation_read");
            RepositoryId repositoryId = new RepositoryId("orders");
            RepositoryRevision oldRevision = new RepositoryRevision("1".repeat(40));
            RepositoryRevision newRevision = new RepositoryRevision("2".repeat(40));
            Document newPointer = pointer(newRevision, "g2", "4".repeat(64));
            seedGeneration(template, repositoryId, oldRevision, "g1", "2".repeat(64), "return \"A\";");
            seedGeneration(template, repositoryId, newRevision, "g2", "4".repeat(64), "return \"B\";");
            seedPointer(template, repositoryId, pointer(oldRevision, "g1", "2".repeat(64)));
            CurrentGenerationSelector currentSelector = selector(template, policy());
            SelectedGenerationGuard guard = guard(template, policy());
            SelectedSemanticQueryService selectedQueries = SelectedSemanticQueryService.create(template, guard, Duration.ofSeconds(2));
            SemanticQueryFacade currentQueries = new SemanticQueryFacade(currentSelector, selectedQueries,
                    new CurrentRepositoryQueryService(currentSelector));

            SemanticQueryContract.SearchCodeRequest request = new SemanticQueryContract.SearchCodeRequest(repositoryId.value(), oldRevision.value(),
                    "Order", Set.of(CodeFactKind.TYPE), Optional.empty(), 0, 20);
            SelectedGeneration selected = currentSelector.select(request.repositoryId(), request.revision(),
                    SelectedGenerationGuard.SEARCH_WITH_SOURCES);
            template.getCollection("repositories").updateOne(new Document("repoId", repositoryId.value()),
                    new Document("$set", new Document("currentPointer", newPointer)));
            SemanticQueryContract.SearchCodeResult result = selectedQueries.searchCode(selected, request);

            assertEquals(oldRevision.value(), result.revision());
            assertTrue(result.items().getFirst().source().code().contains("return \"A\";"));
            assertFalse(result.items().getFirst().source().code().contains("return \"B\";"));
            assertThrows(RevisionOutdatedException.class, () -> currentQueries.searchCode(request));
        }
    }

    private static void seedGeneration(MongoTemplate template, RepositoryId repositoryId, RepositoryRevision revision,
                                       String generationId, String digest, String body) {
        template.getCollection("generation_manifests").insertOne(new Document("repoId", repositoryId.value())
                .append("sourceRevision", revision.value()).append("generationId", generationId).append("identityDigest", digest)
                .append("writeState", "SEALED_VALID").append("schemaVersion", IndexSchemaContract.SCHEMA_VERSION)
                .append("projectionVersions", projectionVersions()));
        String path = "src/main/java/example/orders/Order.java";
        String content = "package example.orders; class Order { String code() { " + body + " } }";
        SourceTypeIdentity type = new SourceTypeIdentity(new JavaTypeIdentity("example.orders", "Order"), path);
        SourceArtifactDocument artifact = SourceArtifactDocument.create(content);
        template.getCollection("source_artifacts").insertOne(new Document("sourceArtifactId", artifact.id().value())
                .append("contentHash", artifact.contentHash()).append("utf8Content", content));
        GenerationFileDocument generationFile = new GenerationFileDocument(repositoryId, new GenerationId(generationId), path, artifact.id(),
                artifact.contentHash(), "", new SourceIndexScope(false, List.of(), List.of(), List.of()));
        Document storedFile = new Document();
        template.getConverter().write(generationFile, storedFile);
        storedFile.put("repoId", repositoryId.value());
        storedFile.put("generationId", generationId);
        storedFile.put("sourcePath", path);
        storedFile.put("extractionIssueCode", "");
        storedFile.put("scopeUsable", false);
        storedFile.put("scopePackages", List.of());
        storedFile.put("scopeClassKeys", List.of());
        storedFile.put("scopeMethodKeys", List.of());
        template.getCollection("generation_files").insertOne(storedFile);
        CodeFactIdentity identity = new CodeFactIdentity(repositoryId, revision, CodeFactKind.TYPE, type);
        CodeFact fact = new CodeFact(CodeFactId.from(identity), identity);
        SymbolDocument symbol = new SymbolDocument(repositoryId, new GenerationId(generationId), fact, CodeFactKind.TYPE,
                type.fullyQualifiedName(), type.javaType().className(), type.canonicalForm(), new DeclaredType(type.fullyQualifiedName()),
                Set.of(), List.of(), artifact.id(), new SourceRange(path, new SyntaxRange(new SyntaxPosition(0, 0), new SyntaxPosition(0, content.length()))));
        Document storedSymbol = new Document();
        template.getConverter().write(symbol, storedSymbol);
        CodeFactScope scope = CodeFactScope.from(identity);
        storedSymbol.put("repoId", repositoryId.value());
        storedSymbol.put("generationId", generationId);
        storedSymbol.put("symbolId", fact.id().value());
        storedSymbol.put("canonical", identity.canonicalForm());
        storedSymbol.put("sourcePath", path);
        storedSymbol.put("scopePackage", scope.packageName());
        storedSymbol.put("scopeClass", scope.className());
        storedSymbol.put("scopeMethod", "");
        storedSymbol.put("scopeParameters", List.of());
        storedSymbol.put("scopePath", scope.sourcePath().orElse(""));
        template.getCollection("symbols").insertOne(storedSymbol);
        template.getCollection("search").insertOne(new Document("repoId", repositoryId.value()).append("generationId", generationId)
                .append("factId", fact.id().value()).append("kind", CodeFactKind.TYPE.name()).append("tokens", List.of("order"))
                .append("package", scope.packageName()).append("authority", "SYMBOLS").append("canonical", identity.canonicalForm())
                .append("scopePackage", scope.packageName()).append("scopeClass", scope.className()).append("scopeMethod", "")
                .append("scopeParameters", List.of()).append("scopePath", scope.sourcePath().orElse("")));
    }

    private static void seedPointer(MongoTemplate template, RepositoryId repositoryId, Document pointer) {
        template.getCollection("repositories").replaceOne(new Document("repoId", repositoryId.value()),
                new Document("repoId", repositoryId.value()).append("currentPointer", pointer), new ReplaceOptions().upsert(true));
    }

    private static Document pointer(RepositoryRevision revision, String generationId, String digest) {
        return new Document("revision", revision.value()).append("generationId", generationId).append("manifestDigest", digest)
                .append("committedJobId", "job-" + generationId).append("publishedAt", new Date());
    }

    private static List<Document> projectionVersions() {
        return IndexSchemaContract.requiredProjectionVersions().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new Document("name", entry.getKey()).append("version", entry.getValue()))
                .toList();
    }
}
