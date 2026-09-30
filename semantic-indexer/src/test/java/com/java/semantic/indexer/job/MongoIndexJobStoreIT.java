package com.java.semantic.indexer.job;

import com.java.semantic.indexer.store.IndexSchemaBootstrap;
import com.java.semantic.indexer.store.GitEvidencePublicationStore;
import com.java.semantic.indexer.build.GenerationValidator;
import com.java.semantic.indexer.store.MongoPublicationWriter;
import com.java.semantic.indexer.store.PublicationConflictException;
import com.java.semantic.model.index.IndexCollections;
import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.index.IndexSchemaContract;
import com.java.semantic.model.index.ManifestDigest;
import com.java.semantic.model.index.PublishGenerationCommand;
import com.java.semantic.model.index.AnalysisFingerprint;
import com.java.semantic.model.index.AnalysisInputs;
import com.java.semantic.model.index.SemanticAnalysisEvidence;
import com.java.semantic.model.query.SelectedGeneration;
import com.java.semantic.model.index.PublishedGenerationPointer;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.model.review.ReviewSide;
import com.java.semantic.model.review.ReviewSelection;
import com.java.semantic.model.review.ResolvedReviewEndpoints;
import com.java.semantic.model.review.ReviewBaselineRule;
import com.java.semantic.model.source.ProjectGuideMembership;
import com.java.semantic.model.source.SourceEvidenceDocumentCodec;
import com.java.semantic.model.source.ProjectGuideState;
import com.java.semantic.model.source.SourceCoverage;
import com.java.semantic.model.source.SourceEvidencePolicy;
import com.java.semantic.model.source.SourceSnapshotMembership;
import com.java.semantic.model.source.SourceStructure;
import org.bson.Document;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.mongodb.MongoDBContainer;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("mongo-it")
class MongoIndexJobStoreIT {
    @Test
    void range_admission_does_not_capture_current_and_prevents_a_second_active_review() {
        try (MongoDBContainer container = container()) {
            MongoTemplate template = template(container);
            MongoIndexJobStore store = store(template);
            RepositoryId repositoryId = RepositoryId.of("orders");
            PublishedGenerationPointer currentC = pointer("c", "g-current-c", "job-current-c");
            PublishedGenerationPointer currentD = pointer("d", "g-current-d", "job-current-d");
            seedPublished(template, repositoryId.value(), currentC, true, IndexSchemaContract.SCHEMA_VERSION);
            ReviewSelection selection = ReviewSelection.range(revision("a"), revision("e"));

            IndexJob accepted = store.admitReview(repositoryId, selection);
            template.getCollection(IndexCollections.REPOSITORIES).updateOne(new Document("repoId", repositoryId.value()),
                    new Document("$set", new Document("currentPointer", pointerDocument(currentD))));

            assertThat(accepted.review().orElseThrow().selection()).isEqualTo(selection);
            assertThat(accepted.review().orElseThrow().resolvedEndpoints()).isEmpty();
            assertThat(store.find(accepted.id()).orElseThrow().review().orElseThrow().selection()).isEqualTo(selection);
            assertThatThrownBy(() -> store.admitReview(repositoryId, ReviewSelection.commit(revision("f"))))
                    .isInstanceOf(IndexJobAlreadyActiveException.class);
        }
    }

    @Test
    void review_without_a_published_current_is_admitted_as_a_durable_job() {
        try (MongoDBContainer container = container()) {
            MongoTemplate template = template(container);
            MongoIndexJobStore store = store(template);
            RepositoryId repositoryId = RepositoryId.of("root-only");
            template.getCollection(IndexCollections.REPOSITORIES).insertOne(new Document("repoId", repositoryId.value()));

            IndexJob accepted = store.admitReview(repositoryId, ReviewSelection.commit(revision("a")));

            assertThat(accepted.operation()).isEqualTo(IndexJobOperation.REVIEW);
            assertThat(accepted.phase()).isEqualTo(IndexJobPhase.ACCEPTED);
            assertThat(store.find(accepted.id())).contains(accepted);
            assertThat(template.getCollection(IndexCollections.INDEX_JOBS)
                    .countDocuments(new Document("jobId", accepted.id().value()).append("repoId", repositoryId.value()))).isEqualTo(1L);
            IndexJob running = store.startNextAccepted().orElseThrow();
            IndexJob resolved = store.resolveReviewEndpoints(running.id(),
                    new ResolvedReviewEndpoints(Optional.empty(), revision("a"), ReviewBaselineRule.EMPTY_TREE));
            assertThat(resolved.review().orElseThrow().reservedTargets().orElseThrow().before()).isEmpty();
            assertThat(resolved.review().orElseThrow().resolvedEndpoints().orElseThrow().baselineRule())
                    .isEqualTo(ReviewBaselineRule.EMPTY_TREE);
        }
    }

    @Test
    void admits_one_active_job_per_repository_and_independent_repositories() {
        try (MongoDBContainer container = container()) {
            MongoIndexJobStore store = store(container);
            RepositoryRevision revision = revision("a");
            IndexJob orders = store.admit(RepositoryId.of("orders"), revision, false);

            assertThatThrownBy(() -> store.admit(RepositoryId.of("orders"), revision, false))
                    .isInstanceOf(IndexJobAlreadyActiveException.class);
            IndexJob payments = store.admit(RepositoryId.of("payments"), revision, false);

            assertThat(orders.active()).isTrue();
            assertThat(payments.active()).isTrue();
        }
    }

    @Test
    void starts_only_accepted_jobs_and_terminal_transitions_require_running_jobs() {
        try (MongoDBContainer container = container()) {
            MongoIndexJobStore store = store(container);
            IndexJob accepted = store.admit(RepositoryId.of("orders"), revision("a"), false);

            assertThat(store.complete(accepted.id())).isFalse();
            assertThat(store.fail(accepted.id(), IndexFailureCategory.WORKER_INTERRUPTED)).isFalse();
            assertThat(store.startNextAccepted()).hasValueSatisfying(job -> assertThat(job.phase()).isEqualTo(IndexJobPhase.RUNNING));
            assertThat(store.startNextAccepted()).isEmpty();
            assertThat(store.complete(accepted.id())).isTrue();
            assertThat(store.complete(accepted.id())).isFalse();
            assertThat(store.fail(accepted.id(), IndexFailureCategory.WORKER_INTERRUPTED)).isFalse();
        }
    }

    @Test
    void start_next_accepted_claims_the_oldest_job_across_repositories_with_a_job_id_tie_break() {
        try (MongoDBContainer container = container()) {
            MongoTemplate template = template(container);
            MongoIndexJobStore store = store(template);
            IndexJob orders = store.admit(RepositoryId.of("orders"), revision("a"), false);
            IndexJob payments = store.admit(RepositoryId.of("payments"), revision("b"), false);
            Date createdAt = Date.from(Instant.parse("2026-08-26T00:00:00Z"));
            template.getCollection(IndexCollections.INDEX_JOBS).updateOne(new Document("jobId", orders.id().value()),
                    new Document("$set", new Document("createdAt", createdAt)));
            template.getCollection(IndexCollections.INDEX_JOBS).updateOne(new Document("jobId", payments.id().value()),
                    new Document("$set", new Document("createdAt", createdAt)));
            IndexJob expected = orders.id().value().compareTo(payments.id().value()) < 0 ? orders : payments;
            IndexJob other = expected.equals(orders) ? payments : orders;

            assertThat(store.startNextAccepted()).hasValueSatisfying(job -> {
                assertThat(job.id()).isEqualTo(expected.id());
                assertThat(job.phase()).isEqualTo(IndexJobPhase.RUNNING);
            });
            assertThat(store.find(other.id()).orElseThrow().phase()).isEqualTo(IndexJobPhase.ACCEPTED);
        }
    }

    @Test
    void start_next_accepted_claims_one_job_at_most_once_when_two_callers_race() throws Exception {
        try (MongoDBContainer container = container(); ExecutorService callers = Executors.newFixedThreadPool(2)) {
            MongoIndexJobStore store = store(container);
            IndexJob accepted = store.admit(RepositoryId.of("orders"), revision("a"), false);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch release = new CountDownLatch(1);
            Future<Optional<IndexJob>> first = callers.submit(() -> claimAfterBarrier(store, ready, release));
            Future<Optional<IndexJob>> second = callers.submit(() -> claimAfterBarrier(store, ready, release));

            assertThat(ready.await(2L, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            List<IndexJob> started = List.of(first.get(2L, TimeUnit.SECONDS), second.get(2L, TimeUnit.SECONDS)).stream()
                    .flatMap(Optional::stream).toList();

            assertThat(started).hasSize(1);
            assertThat(started.getFirst().id()).isEqualTo(accepted.id());
            assertThat(started.getFirst().phase()).isEqualTo(IndexJobPhase.RUNNING);
            assertThat(store.find(accepted.id()).orElseThrow().phase()).isEqualTo(IndexJobPhase.RUNNING);
        }
    }

    @Test
    void build_target_round_trips_as_a_nested_document() {
        try (MongoDBContainer container = container()) {
            MongoTemplate template = template(container);
            MongoIndexJobStore store = store(template);
            IndexJob admitted = store.admit(RepositoryId.of("orders"), revision("a"), false);

            Document document = template.getCollection(IndexCollections.INDEX_JOBS)
                    .find(new Document("jobId", admitted.id().value())).first();

            assertThat(document.get("target", Document.class)).isNotNull();
            assertThat(document.containsKey("revision")).isFalse();
            assertThat(document.containsKey("generationId")).isFalse();
            assertThat(admitted.target()).contains(new IndexJobTarget(revision("a"), admitted.target().orElseThrow().generationId(), 1L));
            assertThat(store.find(admitted.id()).orElseThrow()).isEqualTo(admitted);
        }
    }

    @Test
    void rejects_jobs_without_the_current_persisted_job_contract_version() {
        try (MongoDBContainer container = container()) {
            MongoTemplate template = template(container);
            MongoIndexJobStore store = store(template);
            IndexJob admitted = store.admit(RepositoryId.of("orders"), revision("a"), false);
            template.getCollection(IndexCollections.INDEX_JOBS).updateOne(new Document("jobId", admitted.id().value()),
                    new Document("$unset", new Document("jobVersion", "")));

            assertThatThrownBy(() -> store.find(admitted.id()))
                    .isInstanceOf(IllegalArgumentException.class);
            template.getCollection(IndexCollections.INDEX_JOBS).updateOne(new Document("jobId", admitted.id().value()),
                    new Document("$set", new Document("jobVersion", IndexSchemaContract.PERSISTED_JOB_VERSION)));
            assertThat(store.find(admitted.id())).contains(admitted);
            template.getCollection(IndexCollections.INDEX_JOBS).updateOne(new Document("jobId", admitted.id().value()),
                    new Document("$set", new Document("jobVersion", 1)));

            assertThatThrownBy(() -> store.find(admitted.id()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void activates_only_reserved_review_targets_and_never_reuses_their_ordinals() {
        try (MongoDBContainer container = container()) {
            MongoTemplate template = template(container);
            MongoIndexJobStore store = store(template);
            String jobId = "review-job";
            template.getCollection(IndexCollections.INDEX_JOBS).insertOne(reviewJob(jobId));

            IndexJob activeBefore = store.activateReviewTarget(new IndexJobId(jobId), ReviewSide.BEFORE);

            assertThat(activeBefore.target()).contains(new IndexJobTarget(revision("a"), new GenerationId("g-before"), 5L));
            assertThat(activeBefore.review().orElseThrow().stage()).isEqualTo(ReviewPreparationStage.BUILDING_BEFORE);
            assertThatThrownBy(() -> store.activateReviewTarget(activeBefore.id(), ReviewSide.AFTER))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(store.complete(activeBefore.id())).isTrue();

            IndexJob laterBuild = store.admit(RepositoryId.of("orders"), revision("c"), false);

            assertThat(laterBuild.target().orElseThrow().generation()).isEqualTo(7L);
        }
    }

    @Test
    void ensure_returns_inactive_targetless_no_work_only_for_an_exact_current_generation() {
        try (MongoDBContainer container = container()) {
            MongoTemplate template = template(container);
            MongoIndexJobStore store = store(template);
            RepositoryRevision revision = revision("f");
            seedPublished(template, "exact", pointer("f", "g-exact", "job-exact"), true, IndexSchemaContract.SCHEMA_VERSION);
            seedPublished(template, "stale", pointer("f", "g-stale", "job-stale"), false, IndexSchemaContract.SCHEMA_VERSION);
            seedPublished(template, "incompatible", pointer("f", "g-incompatible", "job-incompatible"), true, 99);

            IndexJob noWork = store.admitEnsure(RepositoryId.of("exact"), revision);
            IndexJob rebuild = store.admitEnsure(RepositoryId.of("stale"), revision);

            assertThat(noWork.operation()).isEqualTo(IndexJobOperation.NO_WORK);
            assertThat(noWork.active()).isFalse();
            assertThat(noWork.phase()).isEqualTo(IndexJobPhase.COMPLETE);
            assertThat(noWork.target()).isEmpty();
            assertThat(rebuild.operation()).isEqualTo(IndexJobOperation.BUILD);
            assertThat(rebuild.rebuild()).isTrue();
            assertThatThrownBy(() -> store.admitEnsure(RepositoryId.of("incompatible"), revision))
                    .isInstanceOf(IndexSchemaRebuildRequiredException.class);
        }
    }

    @Test
    void rejects_stale_rollback_admission_and_generates_running_rollback_command() {
        try (MongoDBContainer container = container()) {
            MongoTemplate template = template(container);
            MongoIndexJobStore store = store(template);
            PublishedGenerationPointer previous = seedValidGeneration(template, RepositoryId.of("orders"),
                    revision("a"), new GenerationId("g-previous"), "job-previous");
            PublishedGenerationPointer current = pointer("b", "g-current", "job-current");
            seedRepositoryWithRollback(template, "orders", current, previous);
            assertThatThrownBy(() -> store.admitRollback(RepositoryId.of("orders"), previous, current))
                    .isInstanceOf(PublicationConflictException.class);
            IndexJob accepted = store.admitRollback(RepositoryId.of("orders"), current, previous);
            assertThat(store.rollbackCommand(accepted)).isEmpty();
            IndexJob running = store.startNextAccepted().orElseThrow();

            assertThat(store.rollbackCommand(running)).hasValueSatisfying(command -> {
                assertThat(command.expectedCurrent()).isEqualTo(current);
                assertThat(command.expectedRollback()).isEqualTo(previous);
                assertThat(command.jobId()).isEqualTo(running.id().value());
            });
            new MongoPublicationWriter(template).rollback(store.rollbackCommand(running).orElseThrow());

            assertThat(store.reconcileCommitted(RepositoryId.of("orders"))).hasValueSatisfying(job -> assertThat(job.phase()).isEqualTo(IndexJobPhase.COMPLETE));
            assertThat(store.reconcileCommitted(RepositoryId.of("orders"))).isEmpty();
        }
    }

    @Test
    void reconciliation_completes_committed_running_jobs_and_fails_only_other_running_jobs() {
        try (MongoDBContainer container = container()) {
            MongoTemplate template = template(container);
            MongoIndexJobStore store = store(template);
            store.admit(RepositoryId.of("committed"), revision("c"), false);
            IndexJob committed = store.startNextAccepted().orElseThrow();
            PublishedGenerationPointer result = seedValidGeneration(template, committed.repositoryId(),
                    committed.target().orElseThrow().revision(), committed.target().orElseThrow().generationId(),
                    committed.id().value());
            ManifestDigest digest = result.manifestDigest();
            IndexPublicationIntent intent = store.prepareBuildPublication(committed, digest).orElseThrow();
            new MongoPublicationWriter(template).publish(buildCommand(committed, intent));
            store.admit(RepositoryId.of("uncommitted"), revision("d"), false);
            IndexJob uncommitted = store.startNextAccepted().orElseThrow();
            IndexJob accepted = store.admit(RepositoryId.of("accepted"), revision("e"), false);

            store.reconcileCommittedJobs();
            store.failUnreconciledRunningJobs();

            assertThat(store.find(committed.id()).orElseThrow().phase()).isEqualTo(IndexJobPhase.COMPLETE);
            assertThat(store.reconcileCommitted(RepositoryId.of("committed"))).isEmpty();
            assertThat(store.find(uncommitted.id()).orElseThrow().phase()).isEqualTo(IndexJobPhase.FAILED);
            assertThat(store.find(uncommitted.id()).orElseThrow().failureCategory()).contains(IndexFailureCategory.WORKER_INTERRUPTED);
            assertThat(store.find(accepted.id()).orElseThrow().phase()).isEqualTo(IndexJobPhase.ACCEPTED);
            assertThat(store.find(accepted.id()).orElseThrow().active()).isTrue();
        }
    }

    @Test
    void git_evidence_uses_the_existing_active_job_constraint_and_recovers_only_ready_manifests() {
        try (MongoDBContainer container = container()) {
            MongoTemplate template = template(container);
            MongoIndexJobStore store = store(template);
            IndexJob accepted = store.admitGitRefs(RepositoryId.of("orders"));

            assertThatThrownBy(() -> store.admit(RepositoryId.of("orders"), revision("a"), false))
                    .isInstanceOf(IndexJobAlreadyActiveException.class);
            IndexJob ready = store.startNextAccepted().orElseThrow();
            String evidenceId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
            template.getCollection(IndexCollections.INDEX_JOBS).updateOne(new Document("jobId", ready.id().value()),
                    new Document("$set", new Document("gitEvidence.evidenceId", evidenceId)));
            template.getCollection(IndexCollections.GIT_EVIDENCE_MANIFESTS).insertOne(new Document("repoId", "orders")
                    .append("evidenceId", evidenceId).append("ownerJobId", ready.id().value()).append("kind", "CATALOG")
                    .append("state", "READY").append("gitEvidenceVersion", IndexSchemaContract.GIT_EVIDENCE_VERSION).append("scope", "STANDALONE").append("observedAt", new Date()).append("total", 0L));

            store.failUnreconciledRunningJobs();

            assertThat(store.find(ready.id()).orElseThrow().phase()).isEqualTo(IndexJobPhase.COMPLETE);
            IndexJob incomplete = store.admitGitRefs(RepositoryId.of("orders"));
            IndexJob running = store.startNextAccepted().orElseThrow();
            store.failUnreconciledRunningJobs();
            assertThat(incomplete.id()).isEqualTo(running.id());
            assertThat(store.find(incomplete.id()).orElseThrow().failureCategory()).contains(IndexFailureCategory.WORKER_INTERRUPTED);
        }
    }

    @Test
    void comparison_recovery_requires_a_ready_matching_comparison_and_both_ready_snapshots() {
        try (MongoDBContainer container = container()) {
            MongoTemplate template = template(container);
            MongoIndexJobStore store = store(template);
            IndexJob accepted = store.admitGitComparison(RepositoryId.of("orders"), revision("a"), revision("b"));
            IndexJob running = store.startNextAccepted().orElseThrow();
            String comparisonId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
            String previousSnapshotId = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
            String currentSnapshotId = "cccccccc-cccc-cccc-cccc-cccccccccccc";
            template.getCollection(IndexCollections.INDEX_JOBS).updateOne(new Document("jobId", running.id().value()),
                    new Document("$set", new Document("gitEvidence.evidenceId", comparisonId)));
            template.getCollection(IndexCollections.GIT_EVIDENCE_MANIFESTS).insertMany(List.of(
                    new Document("repoId", "orders").append("evidenceId", comparisonId).append("ownerJobId", running.id().value())
                            .append("kind", "COMPARISON").append("state", "READY").append("previous", revision("a").value())
                            .append("current", revision("b").value()).append("previousSnapshotId", previousSnapshotId).append("currentSnapshotId", currentSnapshotId),
                    new Document("repoId", "orders").append("evidenceId", previousSnapshotId).append("kind", "SNAPSHOT").append("state", "READY"),
                    new Document("repoId", "orders").append("evidenceId", currentSnapshotId).append("kind", "SNAPSHOT").append("state", "PREPARING")));

            store.failUnreconciledRunningJobs();

            assertThat(store.find(accepted.id()).orElseThrow().phase()).isEqualTo(IndexJobPhase.FAILED);
            IndexJob retry = store.admitGitComparison(RepositoryId.of("orders"), revision("a"), revision("b"));
            assertThat(retry.phase()).isEqualTo(IndexJobPhase.ACCEPTED);
        }
    }

    @Test
    void build_publication_requires_running_job_and_preserves_rebuild_parent_precondition() {
        try (MongoDBContainer container = container()) {
            MongoTemplate template = template(container);
            MongoIndexJobStore store = store(template);
            PublishedGenerationPointer parent = seedValidGeneration(template, RepositoryId.of("orders"),
                    revision("a"), new GenerationId("g-parent"), "job-parent");
            template.getCollection(IndexCollections.REPOSITORIES).insertOne(repositoryDocument("orders", parent));
            IndexJob accepted = store.admitRebuild(RepositoryId.of("orders"), revision("b"), parent);
            ManifestDigest digest = new ManifestDigest("b".repeat(64));

            assertThat(store.prepareBuildPublication(accepted, digest)).isEmpty();
            IndexJob running = store.startNextAccepted().orElseThrow();
            PublishedGenerationPointer built = seedValidGeneration(template, running.repositoryId(),
                    running.target().orElseThrow().revision(), running.target().orElseThrow().generationId(),
                    running.id().value());
            IndexPublicationIntent intent = store.prepareBuildPublication(running, built.manifestDigest()).orElseThrow();
            PublishedGenerationPointer winner = pointer("c", "g-winner", "job-winner");
            template.getCollection(IndexCollections.REPOSITORIES).updateOne(new Document("repoId", "orders"), new Document("$set",
                    new Document("currentPointer", pointerDocument(winner))));

            assertThat(intent.expectedParent()).contains(parent);
            assertThatThrownBy(() -> new MongoPublicationWriter(template).publish(buildCommand(running, intent)))
                    .isInstanceOf(PublicationConflictException.class);
        }
    }

    private static Document reviewJob(String jobId) {
        Document before = new Document("revision", revision("a").value()).append("generationId", "g-before").append("generation", 5L);
        Document after = new Document("revision", revision("b").value()).append("generationId", "g-after").append("generation", 6L);
        Document review = new Document("reviewId", "review-1")
                .append("selection", new Document("kind", "RANGE").append("beforeRevision", revision("a").value())
                        .append("afterRevision", revision("b").value()))
                .append("resolvedEndpoints", new Document("beforeRevision", revision("a").value())
                        .append("afterRevision", revision("b").value()).append("baselineRule", "DIRECT_RANGE"))
                .append("reservedTargets", new Document("before", before).append("after", after))
                .append("stage", ReviewPreparationStage.PREPARING_BEFORE.name());
        return new Document("jobId", jobId).append("repoId", "orders").append("active", true)
                .append("phase", IndexJobPhase.RUNNING.name()).append("operation", IndexJobOperation.REVIEW.name()).append("rebuild", false)
                .append("jobVersion", IndexSchemaContract.PERSISTED_JOB_VERSION).append("generationHighWatermark", 6L)
                .append("review", review).append("createdAt", new Date());
    }

    private static MongoDBContainer container() {
        MongoDBContainer container = new MongoDBContainer("mongo:8.0.4");
        container.start();
        return container;
    }

    private static MongoIndexJobStore store(MongoDBContainer container) {
        return store(template(container));
    }

    private static MongoIndexJobStore store(MongoTemplate template) {
        new IndexSchemaBootstrap(template).bootstrap();
        return new MongoIndexJobStore(template);
    }

    private static Optional<IndexJob> claimAfterBarrier(MongoIndexJobStore store, CountDownLatch ready, CountDownLatch release)
            throws InterruptedException {
        ready.countDown();
        release.await();
        return store.startNextAccepted();
    }

    private static MongoTemplate template(MongoDBContainer container) {
        return new MongoTemplate(com.mongodb.client.MongoClients.create(container.getConnectionString()), "semantic");
    }

    private static RepositoryRevision revision(String character) {
        return new RepositoryRevision(character.repeat(40));
    }

    private static PublishedGenerationPointer seedValidGeneration(MongoTemplate template, RepositoryId repository,
            RepositoryRevision revision, GenerationId generation, String owner) {
        SourceEvidencePolicy policy = new SourceEvidencePolicy(1, List.of(), Set.of(), Optional.empty());
        ProjectGuideMembership guide = ProjectGuideMembership.unavailable(ProjectGuideState.DISABLED);
        IndexJob sourceJob = new IndexJob(IndexJobId.create(), repository,
                Optional.of(new IndexJobTarget(revision, generation, 1L)), IndexJobPhase.RUNNING,
                true, Optional.empty(), false, IndexJobOperation.BUILD);
        SourceSnapshotMembership snapshot = new GitEvidencePublicationStore(template)
                .publishSourceSnapshot(sourceJob, revision, List.of(), policy, guide, Instant.now());
        AnalysisInputs inputs = new AnalysisInputs(IndexSchemaContract.ANALYSIS_EVIDENCE_VERSION,
                "e".repeat(64), "e".repeat(64), "e".repeat(64), "e".repeat(64), List.of());
        AnalysisFingerprint fingerprint = AnalysisFingerprint.from(inputs);
        SemanticAnalysisEvidence evidence = new SemanticAnalysisEvidence(IndexSchemaContract.ANALYSIS_EVIDENCE_VERSION,
                fingerprint.digest(), "SUCCESS", List.of(),
                new SemanticAnalysisEvidence.ResolutionCoverage(0, 0, 0, 0, 0), List.of());
        template.getCollection(IndexCollections.GENERATION_MANIFESTS).insertOne(new Document("repoId", repository.value())
                .append("sourceRevision", revision.value()).append("generationId", generation.value()).append("ownerJobId", owner)
                .append("writeState", "SEALED_VALID").append("writeEpoch", 0L)
                .append("schemaVersion", IndexSchemaContract.SCHEMA_VERSION).append("projectionVersions", projectionVersions())
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
        SelectedGeneration provisional = new SelectedGeneration(repository, revision, generation, new ManifestDigest("0".repeat(64)));
        GenerationValidator.ValidationResult computed = new GenerationValidator(template).validatePersistedSealed(provisional);
        template.getCollection(IndexCollections.GENERATION_MANIFESTS).updateOne(new Document("generationId", generation.value()),
                new Document("$set", new Document("identityDigest", computed.identityDigest().value())
                        .append("sealedCollectionCounts", new Document(computed.collectionCounts()))));
        assertThat(new GenerationValidator(template).validatePersistedSealed(
                new SelectedGeneration(repository, revision, generation, computed.identityDigest())).valid()).isTrue();
        return new PublishedGenerationPointer(revision, generation, computed.identityDigest(), owner, new Date().toInstant());
    }

    private static PublishedGenerationPointer pointer(String revisionCharacter, String generationId, String committedJobId) {
        return new PublishedGenerationPointer(revision(revisionCharacter), new com.java.semantic.model.index.GenerationId(generationId),
                new ManifestDigest(revisionCharacter.repeat(64)), committedJobId, Instant.parse("2026-08-22T00:00:00Z"));
    }

    private static void seedPublished(MongoTemplate template, String repositoryId, PublishedGenerationPointer pointer,
                                      boolean requiredProjections, int schemaVersion) {
        if (requiredProjections && schemaVersion == IndexSchemaContract.SCHEMA_VERSION) {
            PublishedGenerationPointer complete = seedValidGeneration(template, RepositoryId.of(repositoryId),
                    pointer.revision(), pointer.generationId(), pointer.committedJobId());
            template.getCollection(IndexCollections.REPOSITORIES).insertOne(repositoryDocument(repositoryId, complete));
        } else {
            template.getCollection(IndexCollections.REPOSITORIES).insertOne(repositoryDocument(repositoryId, pointer));
            seedManifest(template, repositoryId, pointer, requiredProjections, schemaVersion);
        }
    }

    private static void seedRepositoryWithRollback(MongoTemplate template, String repositoryId,
                                                   PublishedGenerationPointer current, PublishedGenerationPointer rollback) {
        Document repository = repositoryDocument(repositoryId, current);
        repository.append("rollbackPointer", pointerDocument(rollback));
        template.getCollection(IndexCollections.REPOSITORIES).insertOne(repository);
    }

    private static void seedManifest(MongoTemplate template, String repositoryId, PublishedGenerationPointer pointer,
                                     boolean requiredProjections, int schemaVersion) {
        List<Document> projections = requiredProjections ? projectionVersions() : List.of(new Document("name", "SOURCES").append("version", 0));
        template.getCollection(IndexCollections.GENERATION_MANIFESTS).insertOne(new Document("repoId", repositoryId)
                .append("sourceRevision", pointer.revision().value()).append("generationId", pointer.generationId().value())
                .append("ownerJobId", pointer.committedJobId()).append("writeState", "SEALED_VALID").append("writeEpoch", 1L)
                .append("schemaVersion", schemaVersion).append("projectionVersions", projections)
                .append("sealedCollectionCounts", new Document("symbols", 1L)).append("identityDigest", pointer.manifestDigest().value())
                .append("validationResult", "VALID").append("validatedAt", new Date()));
    }

    private static PublishGenerationCommand buildCommand(IndexJob job, IndexPublicationIntent intent) {
        return new PublishGenerationCommand(job.repositoryId(), intent.targetRevision(), intent.targetGenerationId(), job.id().value(),
                intent.expectedParent(), intent.targetManifestDigest());
    }

    private static Document repositoryDocument(String repositoryId, PublishedGenerationPointer pointer) {
        return new Document("repoId", repositoryId).append("currentPointer", pointerDocument(pointer));
    }

    private static Document pointerDocument(PublishedGenerationPointer pointer) {
        return new Document("revision", pointer.revision().value()).append("generationId", pointer.generationId().value())
                .append("manifestDigest", pointer.manifestDigest().value()).append("committedJobId", pointer.committedJobId())
                .append("publishedAt", Date.from(pointer.publishedAt()));
    }

    private static List<Document> projectionVersions() {
        return IndexSchemaContract.requiredProjectionVersions().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey())
                .map(entry -> new Document("name", entry.getKey()).append("version", entry.getValue())).toList();
    }
}
