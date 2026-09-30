package com.java.semantic.query.application;

import com.java.semantic.model.codefact.CodeFactIdentity;
import com.java.semantic.model.codefact.CodeFactKind;
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
class SelectedGenerationReadContractIT extends PublishedMongoITSupport {
    @Test
    void admitted_operation_stays_pinned_when_pointer_moves_and_a_fresh_old_current_request_is_outdated() {
        try (PublishedMongoLifecycle lifecycle = PublishedMongoLifecycle.start(); PublishedMongoLifecycle.Invocation invocation = lifecycle.openInvocation()) {
            MongoTemplate template = invocation.template(); seedCurrent(template, "orders"); String path = "src/main/java/example/Order.java"; seedSource(template, path, "class Order {}\n");
            CodeFactIdentity identity = methodIdentity("example", "Order", "readOrder", path); seedMethod(template, identity, List.of()); seedSearch(template, identity, "SYMBOLS", List.of("read", "order"));
            SelectedSemanticQueryService service = semantic(template, policy());
            SearchCodeRequest request = new SearchCodeRequest(ReadContext.current("orders", REVISION), "readOrder", Set.of(CodeFactKind.METHOD), Optional.empty(), Optional.empty(), new PageRequest(Optional.empty(), 20));
            ReadContextSelector.AdmittedContext context = admitted(template, policy(), service.searchRequirements(request));
            template.getCollection("repositories").updateOne(new Document("repoId", "orders"), new Document("$set", new Document("currentPointer.revision", "2".repeat(40)).append("currentPointer.generationId", "g2")));
            assertThat(service.searchCode(context, request).items()).extracting(CompactFact::displayName).containsExactly("readOrder");
            assertThatThrownBy(() -> admitted(template, policy(), service.searchRequirements(request))).isInstanceOf(RevisionOutdatedException.class);
        }
    }

    @Test
    void current_admission_rejects_rebound_snapshot_revision_generation_policy_or_digest() {
        try (PublishedMongoLifecycle lifecycle = PublishedMongoLifecycle.start(); PublishedMongoLifecycle.Invocation invocation = lifecycle.openInvocation()) {
            MongoTemplate template = invocation.template(); seedCurrent(template, "orders");
            Document filter = new Document("evidenceId", SOURCE_SNAPSHOT);
            Document original = template.getCollection("git_evidence_manifests").find(filter).first();
            for (Document mutation : List.of(new Document("sourceGenerationId", "g2"), new Document("revision", "2".repeat(40)), new Document("policyFingerprint", "f".repeat(64)), new Document("contentDigest", "f".repeat(64)))) {
                template.getCollection("git_evidence_manifests").updateOne(filter, new Document("$set", mutation));
                assertThatThrownBy(() -> admitted(template, policy(), SelectedGenerationGuard.SOURCES)).isInstanceOf(IndexContractMismatchException.class);
                template.getCollection("git_evidence_manifests").replaceOne(filter, original);
            }
        }
    }
}
