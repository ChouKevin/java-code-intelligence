package com.java.semantic.query.application;

import com.java.semantic.model.codefact.CodeFactIdentity;
import com.java.semantic.model.codefact.CodeFactKind;
import com.java.semantic.model.index.IndexSchemaContract;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.bson.Document;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import static com.java.semantic.query.application.SemanticQueryContract.*;
import static org.assertj.core.api.Assertions.*;

@Tag("mongo-it")
class ToolProjectionEvolutionIT extends PublishedMongoITSupport {
    @Test
    void incompatible_search_fails_and_same_sha_rebuild_cannot_resume_old_generation_cursor() {
        try (PublishedMongoLifecycle lifecycle = PublishedMongoLifecycle.start(); PublishedMongoLifecycle.Invocation invocation = lifecycle.openInvocation()) {
            MongoTemplate template = invocation.template(); seedCurrent(template, "orders"); String path = "src/main/java/example/Order.java"; seedSource(template, path, "class Order {}\n");
            for (String name : List.of("readOrder", "readOrderHistory")) { CodeFactIdentity identity = methodIdentity("example", "Order", name, path); seedMethod(template, identity, List.of()); seedSearch(template, identity, "SYMBOLS", List.of("read", "order")); }
            SelectedSemanticQueryService service = semantic(template, policy()); SearchCodeRequest request = search(Optional.empty());
            FactCollection first = service.searchCode(admitted(template, policy(), service.searchRequirements(request)), request);
            List<Document> versions = IndexSchemaContract.requiredProjectionVersions().entrySet().stream().map(entry -> new Document("name", entry.getKey()).append("version", entry.getKey().equals("SEARCH") ? 1 : entry.getValue())).toList();
            template.getCollection("generation_manifests").updateOne(new Document("generationId", "g1"), new Document("$set", new Document("projectionVersions", versions)));
            assertThatThrownBy(() -> admitted(template, policy(), service.searchRequirements(request))).isInstanceOf(IndexContractMismatchException.class);
            template.getCollection("generation_manifests").updateOne(new Document("generationId", "g1"), new Document("$set", new Document("projectionVersions", IndexSchemaContract.requiredProjectionVersions().entrySet().stream().map(entry -> new Document("name", entry.getKey()).append("version", entry.getValue())).toList())));
            Document manifest = new Document(template.getCollection("generation_manifests").find(new Document("generationId", "g1")).first()); manifest.remove("_id"); manifest.put("generationId", "g2"); manifest.put("identityDigest", "3".repeat(64));
            String snapshotId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2";
            Document membership = new Document(manifest.get("sourceSnapshot", Document.class)); membership.put("snapshotId", snapshotId); manifest.put("sourceSnapshot", membership); template.getCollection("generation_manifests").insertOne(manifest);
            Document snapshot = new Document(template.getCollection("git_evidence_manifests").find(new Document("evidenceId", SOURCE_SNAPSHOT)).first()); snapshot.remove("_id"); snapshot.put("evidenceId", snapshotId); snapshot.put("sourceGenerationId", "g2"); template.getCollection("git_evidence_manifests").insertOne(snapshot);
            for (String collection : List.of("symbols", "search", "generation_files")) {
                for (Document old : template.getCollection(collection).find(new Document("generationId", "g1"))) { Document copy = new Document(old); copy.remove("_id"); copy.put("generationId", "g2"); template.getCollection(collection).insertOne(copy); }
            }
            for (Document old : template.getCollection("git_snapshot_files").find(new Document("snapshotId", SOURCE_SNAPSHOT))) { Document copy = new Document(old); copy.remove("_id"); copy.put("snapshotId", snapshotId); template.getCollection("git_snapshot_files").insertOne(copy); }
            template.getCollection("repositories").updateOne(new Document("repoId", "orders"), new Document("$set", new Document("currentPointer.generationId", "g2").append("currentPointer.manifestDigest", "3".repeat(64))));
            ReadContextSelector.AdmittedContext rebuilt = admitted(template, policy(), service.searchRequirements(request));
            assertThat(service.searchCode(rebuilt, request).items()).extracting(CompactFact::displayName).containsExactly("readOrder");
            assertThatThrownBy(() -> service.searchCode(rebuilt, search(first.page().nextCursor()))).isInstanceOf(IllegalArgumentException.class);
        }
    }
    private static SearchCodeRequest search(Optional<String> cursor) { return new SearchCodeRequest(ReadContext.current("orders", REVISION), "readOrder", Set.of(CodeFactKind.METHOD), Optional.empty(), Optional.empty(), new PageRequest(cursor, 1)); }
}
