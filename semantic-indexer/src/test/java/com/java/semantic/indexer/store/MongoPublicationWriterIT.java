package com.java.semantic.indexer.store;

import com.java.semantic.indexer.build.GenerationValidator;
import com.java.semantic.indexer.job.IndexJob;
import com.java.semantic.indexer.job.IndexJobId;
import com.java.semantic.indexer.job.IndexJobOperation;
import com.java.semantic.indexer.job.IndexJobPhase;
import com.java.semantic.indexer.job.IndexJobTarget;
import com.java.semantic.indexer.job.MongoIndexJobStore;
import com.java.semantic.model.index.AnalysisFingerprint;
import com.java.semantic.model.index.AnalysisInputs;
import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.index.IndexCollections;
import com.java.semantic.model.index.IndexSchemaContract;
import com.java.semantic.model.index.ManifestDigest;
import com.java.semantic.model.index.PublishGenerationCommand;
import com.java.semantic.model.index.PublishedGenerationPointer;
import com.java.semantic.model.index.SemanticAnalysisEvidence;
import com.java.semantic.model.query.SelectedGeneration;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.model.source.ProjectGuideMembership;
import com.java.semantic.model.source.ProjectGuideState;
import com.java.semantic.model.source.SourceCoverage;
import com.java.semantic.model.source.SourceEvidencePolicy;
import com.java.semantic.model.source.SourceEvidenceDocumentCodec;
import com.java.semantic.model.source.SourceSnapshotMembership;
import com.java.semantic.model.source.SourceStructure;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.bson.Document;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.mongodb.MongoDBContainer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("mongo-it")
class MongoPublicationWriterIT {
    private static final RepositoryId REPOSITORY = RepositoryId.of("orders");

    @Test
    void publishes_same_revision_rebuild_only_with_expected_parent_and_sealed_source() {
        try (MongoDBContainer container = MongoSchemaTestSupport.container()) {
            MongoTemplate template = bootstrapped(container);
            RepositoryRevision revision = RepositoryRevision.ofSha("a".repeat(40));
            PublishedGenerationPointer first = seed(template, revision, "g1", "job-1");
            MongoPublicationWriter writer = new MongoPublicationWriter(template);
            PublishedGenerationPointer current = writer.publish(command(first, Optional.empty()));
            assertThat(current.generationId()).isEqualTo(new GenerationId("g1"));
            assertThat(new MongoIndexJobStore(template).complete(new IndexJobId(current.committedJobId()))).isTrue();
            PublishedGenerationPointer second = seed(template, revision, "g2", "job-2");
            PublishedGenerationPointer wrongParent = new PublishedGenerationPointer(revision, first.generationId(),
                    new ManifestDigest("f".repeat(64)), first.committedJobId(), first.publishedAt());
            assertThatThrownBy(() -> writer.publish(command(second, Optional.of(wrongParent))))
                    .isInstanceOf(PublicationConflictException.class);
            assertThat(current(template).getString("generationId")).isEqualTo("g1");
            PublishedGenerationPointer replaced = writer.publish(command(second, Optional.of(current)));
            assertThat(replaced.generationId()).isEqualTo(new GenerationId("g2"));
            assertThat(repository(template).get("rollbackPointer", Document.class).getString("generationId")).isEqualTo("g1");
        }
    }

    @Test
    void missing_same_sha_source_snapshot_keeps_the_previous_current_pointer() {
        try (MongoDBContainer container = MongoSchemaTestSupport.container()) {
            MongoTemplate template = bootstrapped(container);
            RepositoryRevision revision = RepositoryRevision.ofSha("a".repeat(40));
            MongoPublicationWriter writer = new MongoPublicationWriter(template);
            PublishedGenerationPointer first = writer.publish(command(seed(template, revision, "g1", "job-1"), Optional.empty()));
            assertThat(new MongoIndexJobStore(template).complete(new IndexJobId(first.committedJobId()))).isTrue();
            PublishedGenerationPointer second = seed(template, revision, "g2", "job-2");
            Document manifest = template.getCollection(IndexCollections.GENERATION_MANIFESTS)
                    .find(new Document("generationId", "g2")).first();
            String snapshotId = template.getConverter().read(SourceSnapshotMembership.class,
                    manifest.get("sourceSnapshot", Document.class)).snapshotId().value();
            template.getCollection(IndexCollections.GIT_EVIDENCE_MANIFESTS)
                    .deleteOne(new Document("evidenceId", snapshotId));

            assertThatThrownBy(() -> writer.publish(command(second, Optional.of(first))))
                    .isInstanceOf(PublicationConflictException.class);
            assertThat(current(template).getString("generationId")).isEqualTo("g1");
        }
    }

    private static MongoTemplate bootstrapped(MongoDBContainer container) {
        MongoTemplate template = MongoSchemaTestSupport.template(container);
        new IndexSchemaBootstrap(template).bootstrap();
        return template;
    }

    private static PublishedGenerationPointer seed(MongoTemplate template, RepositoryRevision revision,
            String generation, String owner) {
        GenerationId generationId = new GenerationId(generation);
        SourceEvidencePolicy policy = new SourceEvidencePolicy(1, List.of(), Set.of(), Optional.empty());
        ProjectGuideMembership guide = ProjectGuideMembership.unavailable(ProjectGuideState.DISABLED);
        IndexJob sourceJob = new IndexJob(IndexJobId.create(), REPOSITORY,
                Optional.of(new IndexJobTarget(revision, generationId, 1L)), IndexJobPhase.RUNNING,
                true, Optional.empty(), false, IndexJobOperation.BUILD);
        SourceSnapshotMembership snapshot = new GitEvidencePublicationStore(template)
                .publishSourceSnapshot(sourceJob, revision, List.of(), policy, guide, Instant.now());
        AnalysisInputs inputs = new AnalysisInputs(IndexSchemaContract.ANALYSIS_EVIDENCE_VERSION,
                "e".repeat(64), "e".repeat(64), "e".repeat(64), "e".repeat(64), List.of());
        AnalysisFingerprint fingerprint = AnalysisFingerprint.from(inputs);
        SemanticAnalysisEvidence evidence = new SemanticAnalysisEvidence(IndexSchemaContract.ANALYSIS_EVIDENCE_VERSION,
                fingerprint.digest(), "SUCCESS", List.of(),
                new SemanticAnalysisEvidence.ResolutionCoverage(0, 0, 0, 0, 0), List.of());
        template.getCollection(IndexCollections.GENERATION_MANIFESTS).insertOne(new Document("repoId", REPOSITORY.value())
                .append("sourceRevision", revision.value()).append("generationId", generation).append("ownerJobId", owner)
                .append("writeState", "SEALED_VALID").append("writeEpoch", 0L)
                .append("schemaVersion", IndexSchemaContract.SCHEMA_VERSION)
                .append("projectionVersions", IndexSchemaContract.requiredProjectionVersions().entrySet().stream()
                        .map(entry -> new Document("name", entry.getKey()).append("version", entry.getValue())).toList())
                .append("sealedCollectionCounts", new Document()).append("identityDigest", "0".repeat(64))
                .append("validationResult", "VALID").append("validatedAt", new Date())
                .append("analysisFingerprint", fingerprint.digest())
                .append("analysisInputs", template.getConverter().convertToMongoType(inputs))
                .append("analysisEvidence", template.getConverter().convertToMongoType(evidence))
                .append("sourceSnapshot", template.getConverter().convertToMongoType(snapshot))
                .append("sourcePolicy", new Document(SourceEvidenceDocumentCodec.encodePolicy(policy)))
                .append("projectGuide", new Document(SourceEvidenceDocumentCodec.encodeGuide(guide)))
                .append("coverage", template.getConverter().convertToMongoType(new SourceCoverage(0, 0, 0, 0)))
                .append("structure", template.getConverter().convertToMongoType(new SourceStructure(List.of(), Map.of(), Map.of()))));
        SelectedGeneration provisional = new SelectedGeneration(REPOSITORY, revision, generationId,
                new ManifestDigest("0".repeat(64)));
        GenerationValidator.ValidationResult computed = new GenerationValidator(template).validatePersistedSealed(provisional);
        template.getCollection(IndexCollections.GENERATION_MANIFESTS).updateOne(new Document("generationId", generation),
                new Document("$set", new Document("identityDigest", computed.identityDigest().value())
                        .append("sealedCollectionCounts", new Document(computed.collectionCounts()))));
        SelectedGeneration selected = new SelectedGeneration(REPOSITORY, revision, generationId, computed.identityDigest());
        assertThat(new GenerationValidator(template).validatePersistedSealed(selected).valid()).isTrue();
        template.getCollection(IndexCollections.INDEX_JOBS).insertOne(new Document("jobId", owner).append("repoId", REPOSITORY.value())
                .append("active", true).append("phase", "RUNNING").append("operation", "BUILD")
                .append("target", new Document("revision", revision.value()).append("generationId", generation).append("generation", 1L)));
        return new PublishedGenerationPointer(revision, generationId, computed.identityDigest(), owner, Instant.now());
    }

    private static PublishGenerationCommand command(PublishedGenerationPointer target,
            Optional<PublishedGenerationPointer> expectedParent) {
        return new PublishGenerationCommand(REPOSITORY, target.revision(), target.generationId(),
                target.committedJobId(), expectedParent, target.manifestDigest());
    }

    private static Document repository(MongoTemplate template) {
        return template.getCollection(IndexCollections.REPOSITORIES).find(new Document("repoId", REPOSITORY.value())).first();
    }

    private static Document current(MongoTemplate template) {
        return repository(template).get("currentPointer", Document.class);
    }
}
