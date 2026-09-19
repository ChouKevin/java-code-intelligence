package com.java.semantic.indexer.review;

import com.java.semantic.indexer.job.IndexJob;
import com.java.semantic.indexer.job.MongoIndexJobStore;
import com.java.semantic.indexer.store.IndexSchemaBootstrap;
import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.index.IndexCollections;
import com.java.semantic.model.index.ManifestDigest;
import com.java.semantic.model.index.PublishedGenerationPointer;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.model.review.ReviewState;
import java.time.Instant;
import java.util.Date;
import org.bson.Document;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.mongodb.MongoDBContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Proves admission grants an identity only; incomplete review artifacts never become readable. */
@Tag("mongo-it")
class ReviewPreparationIT {
    @Test
    void admitted_review_is_not_visible_until_a_complete_ready_manifest_exists() {
        try (MongoDBContainer container = new MongoDBContainer("mongo:8.0.4")) {
            container.start();
            MongoTemplate template = new MongoTemplate(com.mongodb.client.MongoClients.create(container.getConnectionString()), "semantic");
            new IndexSchemaBootstrap(template).bootstrap();
            RepositoryId repositoryId = RepositoryId.of("orders");
            PublishedGenerationPointer current = new PublishedGenerationPointer(new RepositoryRevision("a".repeat(40)),
                    new GenerationId("g-current"), new ManifestDigest("a".repeat(64)), "published-job", Instant.parse("2026-09-19T00:00:00Z"));
            template.getCollection(IndexCollections.REPOSITORIES).insertOne(new Document("repoId", repositoryId.value())
                    .append("currentPointer", pointer(current)));
            MongoIndexJobStore jobs = new MongoIndexJobStore(template);
            IndexJob accepted = jobs.admitReview(repositoryId, new RepositoryRevision("b".repeat(40)));
            ReviewPublicationStore reviews = new ReviewPublicationStore(template, new ReviewReadinessValidator(template));

            reviews.begin(jobs.startNextAccepted().orElseThrow());

            assertThat(accepted.review().orElseThrow().reviewId().value()).isNotBlank();
            assertThat(template.getCollection(IndexCollections.REVIEW_MANIFESTS)
                    .find(new Document("reviewId", accepted.review().orElseThrow().reviewId().value())).first().getString("state"))
                    .isEqualTo(ReviewState.PREPARING.name());
            assertThatThrownBy(() -> reviews.findReady(repositoryId, accepted.review().orElseThrow().reviewId()))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    private static Document pointer(PublishedGenerationPointer pointer) {
        return new Document("revision", pointer.revision().value()).append("generationId", pointer.generationId().value())
                .append("manifestDigest", pointer.manifestDigest().value()).append("committedJobId", pointer.committedJobId())
                .append("publishedAt", Date.from(pointer.publishedAt()));
    }
}
