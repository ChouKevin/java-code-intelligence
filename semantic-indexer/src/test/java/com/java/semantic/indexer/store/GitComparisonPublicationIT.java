package com.java.semantic.indexer.store;

import com.java.semantic.indexer.job.GitEvidenceJob;
import com.java.semantic.indexer.job.IndexJob;
import com.java.semantic.indexer.job.IndexJobId;
import com.java.semantic.indexer.job.IndexJobOperation;
import com.java.semantic.indexer.job.IndexJobPhase;
import com.java.semantic.repository.config.RepositoryProperties;
import com.java.semantic.model.git.GitComparisonAncestry;
import com.java.semantic.model.git.GitChangeKind;
import com.java.semantic.model.git.GitComparisonChange;
import com.java.semantic.model.git.GitPreparedComparison;
import com.java.semantic.model.git.GitSnapshotEntry;
import com.java.semantic.model.git.GitFileContentStatus;
import com.java.semantic.model.git.GitComparisonId;
import com.java.semantic.model.git.GitEvidenceOwnership;
import com.java.semantic.model.git.GitSnapshotId;
import com.java.semantic.model.index.IndexCollections;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.mongodb.MongoDBContainer;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("mongo-it")
class GitComparisonPublicationIT {
    @Test
    void publishes_two_ready_snapshots_before_a_ready_comparison_without_persisting_unreadable_bytes() {
        try (MongoDBContainer container = MongoSchemaTestSupport.container()) {
            org.springframework.data.mongodb.core.MongoTemplate template = MongoSchemaTestSupport.template(container);
            new IndexSchemaBootstrap(template).bootstrap();
            RepositoryId repository = RepositoryId.of("orders");
            RepositoryRevision previous = RepositoryRevision.ofSha("1".repeat(40));
            RepositoryRevision current = RepositoryRevision.ofSha("2".repeat(40));
            IndexJob job = comparisonJob(repository, previous, current);
            GitSnapshotEntry text = new GitSnapshotEntry("README.md", "100644", "3".repeat(40), GitFileContentStatus.TEXT,
                    "context\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            GitSnapshotEntry binary = new GitSnapshotEntry("logo.bin", "100644", "4".repeat(40), GitFileContentStatus.BINARY,
                    new byte[] {1, 0, 2});
            GitComparisonChange change = new GitComparisonChange("change-0", GitChangeKind.ADD, "", "logo.bin", "", "100644", "",
                    "4".repeat(40), "", "UNAVAILABLE");

            new GitEvidencePublicationStore(template).publishComparison(job, new GitPreparedComparison(Optional.of(previous), current,
                    GitComparisonAncestry.PREVIOUS_ANCESTOR, List.of(text), List.of(text, binary), List.of(change)), Instant.now(),
                    GitEvidenceOwnership.standalone());

            assertThat(template.getCollection(IndexCollections.GIT_EVIDENCE_MANIFESTS)
                    .countDocuments(new org.bson.Document("state", "READY"))).isEqualTo(3L);
            assertThat(template.getCollection(IndexCollections.GIT_SNAPSHOT_CHUNKS)
                    .countDocuments(new org.bson.Document("path", "logo.bin"))).isZero();
        }
    }

    @Test
    void refuses_tampered_missing_cross_identity_or_non_ready_rows_before_comparison_ready() {
        try (MongoDBContainer container = MongoSchemaTestSupport.container()) {
            org.springframework.data.mongodb.core.MongoTemplate template = MongoSchemaTestSupport.template(container);
            new IndexSchemaBootstrap(template).bootstrap();
            RepositoryId repository = RepositoryId.of("orders");
            RepositoryRevision previous = RepositoryRevision.ofSha("1".repeat(40));
            RepositoryRevision current = RepositoryRevision.ofSha("2".repeat(40));
            IndexJob job = comparisonJob(repository, previous, current);
            GitSnapshotEntry entry = new GitSnapshotEntry("README.md", "100644", "3".repeat(40), GitFileContentStatus.TEXT,
                    "context\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            GitComparisonChange change = new GitComparisonChange("change-0", GitChangeKind.MODIFY, "README.md", "README.md", "100644",
                    "100644", "3".repeat(40), "4".repeat(40), "@@ -1 +1 @@\n-context\n+changed\n", "AVAILABLE");
            GitPreparedComparison prepared = new GitPreparedComparison(Optional.of(previous), current, GitComparisonAncestry.PREVIOUS_ANCESTOR,
                    List.of(entry), List.of(entry), List.of(change));
            GitEvidencePublicationStore store = new GitEvidencePublicationStore(template);
            store.publishComparison(job, prepared, Instant.now(), GitEvidenceOwnership.standalone());
            org.bson.Document manifest = template.getCollection(IndexCollections.GIT_EVIDENCE_MANIFESTS)
                    .find(new org.bson.Document("kind", "COMPARISON")).first();
            GitComparisonId comparisonId = new GitComparisonId(manifest.getString("evidenceId"));
            GitSnapshotId previousSnapshot = new GitSnapshotId(manifest.getString("previousSnapshotId"));
            GitSnapshotId currentSnapshot = new GitSnapshotId(manifest.getString("currentSnapshotId"));
            template.getCollection(IndexCollections.GIT_EVIDENCE_MANIFESTS).updateOne(new org.bson.Document("evidenceId", comparisonId.value()),
                    new org.bson.Document("$set", new org.bson.Document("state", "PREPARING")));
            template.getCollection(IndexCollections.GIT_COMPARISON_PATCHES).deleteOne(new org.bson.Document("comparisonId", comparisonId.value()));

            assertThatThrownBy(() -> store.validateComparisonPublication(repository, comparisonId, previousSnapshot, currentSnapshot, prepared))
                    .isInstanceOf(PublicationConflictException.class);

            template.getCollection(IndexCollections.GIT_COMPARISON_PATCHES).insertOne(new org.bson.Document("repoId", repository.value())
                    .append("comparisonId", comparisonId.value()).append("changeId", change.changeId()).append("ordinal", 0L).append("patch", change.patch()));
            template.getCollection(IndexCollections.GIT_SNAPSHOT_FILES).updateOne(new org.bson.Document("snapshotId", currentSnapshot.value()),
                    new org.bson.Document("$set", new org.bson.Document("snapshotId", "foreign-snapshot")));

            assertThatThrownBy(() -> store.validateComparisonPublication(repository, comparisonId, previousSnapshot, currentSnapshot, prepared))
                    .isInstanceOf(PublicationConflictException.class);

            template.getCollection(IndexCollections.GIT_SNAPSHOT_FILES).updateOne(new org.bson.Document("snapshotId", "foreign-snapshot"),
                    new org.bson.Document("$set", new org.bson.Document("snapshotId", currentSnapshot.value())));
            template.getCollection(IndexCollections.GIT_EVIDENCE_MANIFESTS).updateOne(new org.bson.Document("evidenceId", currentSnapshot.value()),
                    new org.bson.Document("$set", new org.bson.Document("state", "PREPARING")));

            assertThatThrownBy(() -> store.validateComparisonPublication(repository, comparisonId, previousSnapshot, currentSnapshot, prepared))
                    .isInstanceOf(PublicationConflictException.class);
        }
    }

    @Test
    void refuses_a_ready_snapshot_with_a_missing_text_chunk_position_checkpoint() {
        try (MongoDBContainer container = MongoSchemaTestSupport.container()) {
            org.springframework.data.mongodb.core.MongoTemplate template = MongoSchemaTestSupport.template(container);
            new IndexSchemaBootstrap(template).bootstrap();
            RepositoryId repository = RepositoryId.of("orders");
            RepositoryRevision previous = RepositoryRevision.ofSha("1".repeat(40));
            RepositoryRevision current = RepositoryRevision.ofSha("2".repeat(40));
            IndexJob job = comparisonJob(repository, previous, current);
            GitSnapshotEntry entry = new GitSnapshotEntry("README.md", "100644", "3".repeat(40), GitFileContentStatus.TEXT,
                    "context\\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            GitComparisonChange change = new GitComparisonChange("change-0", GitChangeKind.MODIFY, "README.md", "README.md", "100644",
                    "100644", "3".repeat(40), "4".repeat(40), "@@ -1 +1 @@\\n-context\\n+changed\\n", "AVAILABLE");
            GitPreparedComparison prepared = new GitPreparedComparison(Optional.of(previous), current, GitComparisonAncestry.PREVIOUS_ANCESTOR,
                    List.of(entry), List.of(entry), List.of(change));
            GitEvidencePublicationStore store = new GitEvidencePublicationStore(template);
            store.publishComparison(job, prepared, Instant.now(), GitEvidenceOwnership.standalone());
            org.bson.Document manifest = template.getCollection(IndexCollections.GIT_EVIDENCE_MANIFESTS)
                    .find(new org.bson.Document("kind", "COMPARISON")).first();
            GitComparisonId comparisonId = new GitComparisonId(manifest.getString("evidenceId"));
            GitSnapshotId previousSnapshot = new GitSnapshotId(manifest.getString("previousSnapshotId"));
            GitSnapshotId currentSnapshot = new GitSnapshotId(manifest.getString("currentSnapshotId"));
            template.getCollection(IndexCollections.GIT_EVIDENCE_MANIFESTS).updateOne(new org.bson.Document("evidenceId", comparisonId.value()),
                    new org.bson.Document("$set", new org.bson.Document("state", "PREPARING")));
            template.getCollection(IndexCollections.GIT_SNAPSHOT_CHUNKS).updateOne(new org.bson.Document("snapshotId", currentSnapshot.value()),
                    new org.bson.Document("$set", new org.bson.Document("byteOffset", 0.5D)));

            assertThatThrownBy(() -> store.validateComparisonPublication(repository, comparisonId, previousSnapshot, currentSnapshot, prepared))
                    .isInstanceOf(PublicationConflictException.class);
            template.getCollection(IndexCollections.GIT_SNAPSHOT_CHUNKS).updateOne(new org.bson.Document("snapshotId", currentSnapshot.value()),
                    new org.bson.Document("$unset", new org.bson.Document("byteOffset", "")));

            assertThatThrownBy(() -> store.validateComparisonPublication(repository, comparisonId, previousSnapshot, currentSnapshot, prepared))
                    .isInstanceOf(PublicationConflictException.class);
        }
    }

    @Test
    void seals_the_configured_limits_and_coverage_with_the_snapshot_even_after_settings_change() {
        try (MongoDBContainer container = MongoSchemaTestSupport.container()) {
            org.springframework.data.mongodb.core.MongoTemplate template = MongoSchemaTestSupport.template(container);
            new IndexSchemaBootstrap(template).bootstrap();
            RepositoryProperties properties = new RepositoryProperties();
            properties.setGitEvidenceFileTextBytes(64L);
            properties.setGitEvidenceSnapshotTextBytes(128L);
            RepositoryId repository = RepositoryId.of("orders");
            RepositoryRevision previous = RepositoryRevision.ofSha("1".repeat(40));
            RepositoryRevision current = RepositoryRevision.ofSha("2".repeat(40));
            GitSnapshotEntry text = new GitSnapshotEntry("README.md", "100644", "3".repeat(40), GitFileContentStatus.TEXT,
                    "context\\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            GitPreparedComparison prepared = new GitPreparedComparison(Optional.of(previous), current, GitComparisonAncestry.SAME,
                    List.of(text), List.of(text), List.of());

            new GitEvidencePublicationStore(template, properties).publishComparison(comparisonJob(repository, previous, current), prepared,
                    Instant.now(), GitEvidenceOwnership.standalone());
            properties.setGitEvidenceFileTextBytes(32L);
            properties.setGitEvidenceSnapshotTextBytes(64L);

            List<org.bson.Document> snapshots = template.getCollection(IndexCollections.GIT_EVIDENCE_MANIFESTS)
                    .find(new org.bson.Document("kind", "SNAPSHOT")).into(new java.util.ArrayList<>());
            assertThat(snapshots).hasSize(2).allSatisfy(snapshot -> {
                assertThat(snapshot.getLong("fileTextBytesLimit")).isEqualTo(64L);
                assertThat(snapshot.getLong("snapshotTextBytesLimit")).isEqualTo(128L);
                org.bson.Document coverage = snapshot.get("contentCoverage", org.bson.Document.class);
                assertThat(coverage.getLong("textBytes")).isEqualTo(9L);
                assertThat(coverage.getLong("textEntries")).isEqualTo(1L);
                assertThat(coverage.getLong("entryCount")).isEqualTo(1L);
            });
        }
    }

    @Test
    void persists_an_available_patch_larger_than_one_chunk_without_truncation_or_invalid_utf8() {
        try (MongoDBContainer container = MongoSchemaTestSupport.container()) {
            org.springframework.data.mongodb.core.MongoTemplate template = MongoSchemaTestSupport.template(container);
            new IndexSchemaBootstrap(template).bootstrap();
            RepositoryId repository = RepositoryId.of("orders");
            RepositoryRevision previous = RepositoryRevision.ofSha("1".repeat(40));
            RepositoryRevision current = RepositoryRevision.ofSha("2".repeat(40));
            GitSnapshotEntry entry = new GitSnapshotEntry("README.md", "100644", "3".repeat(40), GitFileContentStatus.TEXT,
                    "context\\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String patch = "測".repeat(40_000);
            List<String> patchChunks = List.of("測".repeat(20_000), "測".repeat(20_000));
            GitComparisonChange change = new GitComparisonChange("change-0", GitChangeKind.MODIFY, "README.md", "README.md", "100644",
                    "100644", "3".repeat(40), "4".repeat(40), patchChunks, "AVAILABLE");

            new GitEvidencePublicationStore(template).publishComparison(comparisonJob(repository, previous, current), new GitPreparedComparison(Optional.of(previous),
                    current, GitComparisonAncestry.PREVIOUS_ANCESTOR, List.of(entry), List.of(entry), List.of(change)), Instant.now(),
                    GitEvidenceOwnership.standalone());

            List<org.bson.Document> chunks = template.getCollection(IndexCollections.GIT_COMPARISON_PATCHES)
                    .find(new org.bson.Document("changeId", change.changeId())).sort(new org.bson.Document("ordinal", 1)).into(new java.util.ArrayList<>());
            assertThat(chunks).hasSizeGreaterThan(1);
            assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.getString("patch").getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                    .isLessThanOrEqualTo(64 * 1024));
            assertThat(chunks.stream().map(chunk -> chunk.getString("patch")).collect(java.util.stream.Collectors.joining())).isEqualTo(patch);
        }
    }

    private static IndexJob comparisonJob(RepositoryId repository, RepositoryRevision previous, RepositoryRevision current) {
        return new IndexJob(IndexJobId.create(), repository, Optional.empty(), IndexJobPhase.RUNNING, true, Optional.empty(), false,
                IndexJobOperation.GIT_COMPARISON, Optional.of(GitEvidenceJob.comparison(previous, current)));
    }
}
