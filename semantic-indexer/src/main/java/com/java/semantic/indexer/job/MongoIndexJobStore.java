package com.java.semantic.indexer.job;

import com.java.semantic.indexer.store.PublicationConflictException;
import com.java.semantic.indexer.review.ReviewBaselineUnavailableException;
import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.index.IndexCollections;
import com.java.semantic.model.index.IndexSchemaContract;
import com.java.semantic.model.index.ManifestDigest;
import com.java.semantic.model.index.PublishedGenerationPointer;
import com.java.semantic.model.index.RollbackGenerationCommand;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.model.git.GitEvidenceId;
import com.java.semantic.model.git.GitComparisonId;
import com.java.semantic.model.git.GitSnapshotId;
import com.java.semantic.model.index.SealedGeneration;
import com.java.semantic.model.review.CapturedReviewBaseline;
import com.java.semantic.model.review.ReviewId;
import com.java.semantic.model.review.ReviewSide;
import com.mongodb.DuplicateKeyException;
import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.time.Instant;
import java.util.UUID;

/** Durable single-process job queue. Repository documents contain pointers only. */
@Component
public final class MongoIndexJobStore implements IndexJobStore {
    private static final String JOB_ID = "jobId";
    private static final String REPOSITORY_ID = "repoId";
    private static final String ACTIVE = "active";
    private final MongoTemplate template;

    public MongoIndexJobStore(MongoTemplate template) {
        this.template = Objects.requireNonNull(template, "mongo template is required");
    }

    @Override
    public IndexJob admit(RepositoryId repositoryId, RepositoryRevision revision, boolean rebuild) {
        Optional<PublishedGenerationPointer> expectedParent = publicationState(repositoryId)
                .flatMap(IndexPublicationState::currentPointer);
        return insertBuild(repositoryId, revision, rebuild, expectedParent);
    }

    @Override
    public IndexJob admitRebuild(RepositoryId repositoryId, RepositoryRevision revision, PublishedGenerationPointer expectedCurrent) {
        Objects.requireNonNull(expectedCurrent, "expected current pointer is required");
        if (findRepository(repositoryId, expectedCurrent, Optional.empty()).isEmpty()) {
            throw new PublicationConflictException();
        }
        return insertBuild(repositoryId, revision, true, Optional.of(expectedCurrent));
    }

    @Override
    public IndexJob admitEnsure(RepositoryId repositoryId, RepositoryRevision revision) {
        Optional<PublishedGenerationPointer> current = publicationState(repositoryId).flatMap(IndexPublicationState::currentPointer);
        if (current.isEmpty()) {
            return admit(repositoryId, revision, false);
        }
        PublishedGenerationPointer pointer = current.orElseThrow();
        Document manifest = template.getCollection(IndexCollections.GENERATION_MANIFESTS).find(new Document(REPOSITORY_ID, repositoryId.value())
                .append("generationId", pointer.generationId().value()).append("sourceRevision", pointer.revision().value())
                .append("identityDigest", pointer.manifestDigest().value()).append("writeState", "SEALED_VALID")).first();
        if (Objects.isNull(manifest) || !revision.equals(pointer.revision())) {
            return admit(repositoryId, revision, false);
        }
        Number schemaVersion = manifest.get("schemaVersion", Number.class);
        if (Objects.isNull(schemaVersion) || schemaVersion.intValue() != IndexSchemaContract.SCHEMA_VERSION) {
            throw new IndexSchemaRebuildRequiredException();
        }
        if (!hasRequiredProjections(manifest)) {
            return admit(repositoryId, revision, true);
        }
        return insertNoWork(repositoryId);
    }

    @Override
    public IndexJob admitRollback(RepositoryId repositoryId, PublishedGenerationPointer expectedCurrent,
                                  PublishedGenerationPointer expectedRollback) {
        Objects.requireNonNull(expectedCurrent, "expected current pointer is required");
        Objects.requireNonNull(expectedRollback, "expected rollback pointer is required");
        if (findRepository(repositoryId, expectedCurrent, Optional.of(expectedRollback)).isEmpty() || !sealed(repositoryId, expectedRollback)) {
            throw new PublicationConflictException();
        }
        IndexJobId jobId = IndexJobId.create();
        IndexJobTarget target = new IndexJobTarget(expectedRollback.revision(), expectedRollback.generationId(), nextGeneration(repositoryId));
        Document job = targetDocument(jobId, repositoryId, target, IndexJobOperation.ROLLBACK, false)
                .append("expectedCurrent", pointerDocument(expectedCurrent)).append("expectedRollback", pointerDocument(expectedRollback));
        job.append("publicationIntent", intentDocument(new IndexPublicationIntent(jobId, IndexJobOperation.ROLLBACK, repositoryId,
                expectedRollback.revision(), expectedRollback.generationId(), expectedRollback.manifestDigest(), Optional.empty(),
                Optional.of(expectedCurrent), Optional.of(expectedRollback))));
        insert(job, repositoryId);
        return from(job);
    }

    @Override
    public IndexJob admitReset(RepositoryId repositoryId) {
        Objects.requireNonNull(repositoryId, "repository id is required");
        IndexJobId jobId = IndexJobId.create();
        Document job = new Document(JOB_ID, jobId.value()).append(REPOSITORY_ID, repositoryId.value()).append(ACTIVE, true)
                .append("phase", IndexJobPhase.ACCEPTED.name()).append("operation", IndexJobOperation.RESET.name())
                .append("rebuild", false).append("jobVersion", IndexSchemaContract.PERSISTED_JOB_VERSION).append("createdAt", new Date());
        insert(job, repositoryId);
        return from(job);
    }

    @Override
    public IndexJob admitGitRefs(RepositoryId repositoryId) {
        return insertGitJob(repositoryId, IndexJobOperation.GIT_REFS, GitEvidenceJob.refs());
    }

    @Override
    public IndexJob admitGitHistory(RepositoryId repositoryId, GitEvidenceId catalogId, String branch, RepositoryRevision revision) {
        return insertGitJob(repositoryId, IndexJobOperation.GIT_HISTORY, GitEvidenceJob.history(catalogId, branch, revision));
    }

    @Override
    public IndexJob admitGitComparison(RepositoryId repositoryId, RepositoryRevision previous, RepositoryRevision current) {
        return insertGitJob(repositoryId, IndexJobOperation.GIT_COMPARISON, GitEvidenceJob.comparison(previous, current));
    }

    @Override
    public IndexJob admitReview(RepositoryId repositoryId, RepositoryRevision revision) {
        RepositoryId requiredRepositoryId = Objects.requireNonNull(repositoryId, "repository id is required");
        RepositoryRevision requiredRevision = Objects.requireNonNull(revision, "requested review revision is required");
        PublishedGenerationPointer baseline = publicationState(requiredRepositoryId).flatMap(IndexPublicationState::currentPointer)
                .orElseThrow(() -> new ReviewBaselineUnavailableException(requiredRepositoryId));
        Instant capturedAt = Instant.now();
        IndexJobId jobId = IndexJobId.create();
        long aGeneration = nextGeneration(requiredRepositoryId);
        long bGeneration = aGeneration + 1L;
        String suffix = jobId.value().replace("-", "");
        IndexJobTarget a = new IndexJobTarget(baseline.revision(), new GenerationId("g-" + suffix + "-a"), aGeneration);
        IndexJobTarget b = new IndexJobTarget(requiredRevision, new GenerationId("g-" + suffix + "-b"), bGeneration);
        Document review = new Document("reviewId", UUID.randomUUID().toString())
                .append("baseline", new Document("pointer", pointerDocument(baseline)).append("capturedAt", Date.from(capturedAt)))
                .append("requestedRevision", requiredRevision.value())
                .append("reservedTargets", new Document("a", targetDocument(a)).append("b", targetDocument(b)))
                .append("stage", ReviewPreparationStage.PREPARING_A.name());
        Document job = new Document(JOB_ID, jobId.value()).append(REPOSITORY_ID, requiredRepositoryId.value()).append(ACTIVE, true)
                .append("phase", IndexJobPhase.ACCEPTED.name()).append("operation", IndexJobOperation.REVIEW.name()).append("rebuild", false)
                .append("generationHighWatermark", bGeneration).append("jobVersion", IndexSchemaContract.PERSISTED_JOB_VERSION)
                .append("review", review).append("createdAt", Date.from(capturedAt));
        insert(job, requiredRepositoryId);
        return from(job);
    }

    @Override
    public IndexJob beginReviewValidation(IndexJobId jobId) {
        IndexJobId requiredJobId = Objects.requireNonNull(jobId, "job id is required");
        Document filter = new Document(JOB_ID, requiredJobId.value()).append(ACTIVE, true).append("phase", IndexJobPhase.RUNNING.name())
                .append("operation", IndexJobOperation.REVIEW.name()).append("review.stage", ReviewPreparationStage.PREPARING_GIT.name())
                .append("review.a", new Document("$exists", true)).append("review.b", new Document("$exists", true))
                .append("review.comparisonId", new Document("$exists", true)).append("review.previousSnapshotId", new Document("$exists", true))
                .append("review.currentSnapshotId", new Document("$exists", true));
        Document updated = template.getCollection(IndexCollections.INDEX_JOBS).findOneAndUpdate(filter,
                Updates.set("review.stage", ReviewPreparationStage.VALIDATING.name()),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        if (Objects.isNull(updated)) {
            throw new IllegalStateException("review validation requires complete prepared evidence");
        }
        return from(updated);
    }

    @Override
    public IndexJob recordReviewReady(IndexJobId jobId) {
        IndexJobId requiredJobId = Objects.requireNonNull(jobId, "job id is required");
        Document running = template.getCollection(IndexCollections.INDEX_JOBS).find(new Document(JOB_ID, requiredJobId.value())
                .append(ACTIVE, true).append("phase", IndexJobPhase.RUNNING.name()).append("operation", IndexJobOperation.REVIEW.name())
                .append("review.stage", ReviewPreparationStage.VALIDATING.name())).first();
        if (Objects.isNull(running) || !reviewManifestReady(running)) {
            throw new IllegalStateException("review ready transition requires a complete ready manifest");
        }
        Document updated = template.getCollection(IndexCollections.INDEX_JOBS).findOneAndUpdate(new Document(JOB_ID, requiredJobId.value())
                        .append(ACTIVE, true).append("phase", IndexJobPhase.RUNNING.name()).append("operation", IndexJobOperation.REVIEW.name())
                        .append("review.stage", ReviewPreparationStage.VALIDATING.name()),
                Updates.set("review.stage", ReviewPreparationStage.READY.name()),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        if (Objects.isNull(updated)) {
            throw new IllegalStateException("review ready transition lost ownership");
        }
        return from(updated);
    }

    @Override
    public IndexJob activateReviewTarget(IndexJobId jobId, ReviewSide side) {
        IndexJobId requiredJobId = Objects.requireNonNull(jobId, "job id is required");
        ReviewSide requiredSide = Objects.requireNonNull(side, "review side is required");
        ReviewPreparationStage preparing = requiredSide == ReviewSide.A ? ReviewPreparationStage.PREPARING_A : ReviewPreparationStage.PREPARING_B;
        ReviewPreparationStage building = requiredSide == ReviewSide.A ? ReviewPreparationStage.BUILDING_A : ReviewPreparationStage.BUILDING_B;
        Document candidate = template.getCollection(IndexCollections.INDEX_JOBS).find(new Document(JOB_ID, requiredJobId.value())).first();
        if (Objects.isNull(candidate)) {
            throw new IllegalStateException("review target activation requires a persisted review");
        }
        Document review = Objects.requireNonNull(candidate.get("review", Document.class), "review payload is required");
        Document reservedTargets = Objects.requireNonNull(review.get("reservedTargets", Document.class), "reserved targets are required");
        IndexJobTarget target = targetFrom(Objects.requireNonNull(reservedTargets.get(requiredSide == ReviewSide.A ? "a" : "b", Document.class),
                "reserved target is required"));
        Document filter = new Document(JOB_ID, requiredJobId.value()).append(ACTIVE, true).append("phase", IndexJobPhase.RUNNING.name())
                .append("operation", IndexJobOperation.REVIEW.name()).append("review.stage", preparing.name())
                .append("target", new Document("$exists", false))
                .append("$expr", new Document("$and", List.of(noBatches("outstandingBatches"), noBatches("failedOrAmbiguousBatches"))));
        Document update = new Document("$set", new Document("target", targetDocument(target)).append("review.stage", building.name()));
        Document activated = template.getCollection(IndexCollections.INDEX_JOBS).findOneAndUpdate(filter, update,
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        if (Objects.isNull(activated)) {
            throw new IllegalStateException("review target activation lost its preparation ownership");
        }
        return from(activated);
    }

    @Override
    public IndexJob recordReviewSide(IndexJobId jobId, ReviewSide side, SealedGeneration generation) {
        IndexJobId requiredJobId = Objects.requireNonNull(jobId, "job id is required");
        ReviewSide requiredSide = Objects.requireNonNull(side, "review side is required");
        SealedGeneration requiredGeneration = Objects.requireNonNull(generation, "sealed generation is required");
        Document running = template.getCollection(IndexCollections.INDEX_JOBS).find(new Document(JOB_ID, requiredJobId.value())
                .append(ACTIVE, true).append("phase", IndexJobPhase.RUNNING.name()).append("operation", IndexJobOperation.REVIEW.name())).first();
        if (Objects.isNull(running)) {
            throw new IllegalStateException("review side record requires an active running review");
        }
        ReviewJobPayload payload = reviewFrom(Objects.requireNonNull(running.get("review", Document.class), "review payload is required"));
        ReviewPreparationStage building = requiredSide == ReviewSide.A ? ReviewPreparationStage.BUILDING_A : ReviewPreparationStage.BUILDING_B;
        ReviewPreparationStage next = requiredSide == ReviewSide.A ? ReviewPreparationStage.PREPARING_B : ReviewPreparationStage.PREPARING_GIT;
        IndexJobTarget target = payload.reservedTargets().target(requiredSide);
        if (payload.stage() != building || !target.equals(targetFrom(Objects.requireNonNull(running.get("target", Document.class), "active target is required")))
                || !requiredGeneration.selected().repositoryId().equals(RepositoryId.of(running.getString(REPOSITORY_ID)))
                || !requiredGeneration.selected().revision().equals(target.revision())) {
            throw new IllegalStateException("review side record does not match its fixed active target");
        }
        requireCompatibleReviewGeneration(running, requiredGeneration, target);
        String sideField = requiredSide == ReviewSide.A ? "a" : "b";
        Document filter = new Document(JOB_ID, requiredJobId.value()).append(ACTIVE, true).append("phase", IndexJobPhase.RUNNING.name())
                .append("operation", IndexJobOperation.REVIEW.name()).append("review.stage", building.name())
                .append("target", targetDocument(target))
                .append("$expr", new Document("$and", List.of(noBatches("outstandingBatches"), noBatches("failedOrAmbiguousBatches"))));
        Document update = new Document("$set", new Document("review." + sideField, template.getConverter().convertToMongoType(requiredGeneration))
                .append("review.stage", next.name()))
                .append("$unset", new Document("target", "").append("outstandingBatches", "").append("acknowledgedBatches", ""));
        Document recorded = template.getCollection(IndexCollections.INDEX_JOBS).findOneAndUpdate(filter, update,
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        if (Objects.isNull(recorded)) {
            throw new IllegalStateException("review side record lost its build ownership");
        }
        return from(recorded);
    }

    @Override
    public Optional<IndexJob> find(IndexJobId jobId) {
        return Optional.ofNullable(template.getCollection(IndexCollections.INDEX_JOBS).find(new Document(JOB_ID, jobId.value())).first())
                .map(this::from);
    }

    @Override
    public Optional<IndexJob> startNextAccepted() {
        Document started = template.getCollection(IndexCollections.INDEX_JOBS).findOneAndUpdate(
                new Document(ACTIVE, true).append("phase", IndexJobPhase.ACCEPTED.name()),
                Updates.set("phase", IndexJobPhase.RUNNING.name()),
                new FindOneAndUpdateOptions().sort(new Document("createdAt", 1).append(JOB_ID, 1))
                        .returnDocument(ReturnDocument.AFTER));
        return Optional.ofNullable(started).map(this::from);
    }

    @Override
    public boolean complete(IndexJobId jobId) { return terminal(jobId, IndexJobPhase.COMPLETE, Optional.empty()); }

    @Override
    public boolean fail(IndexJobId jobId, IndexFailureCategory category) {
        return terminal(jobId, IndexJobPhase.FAILED, Optional.of(Objects.requireNonNull(category, "failure category is required")));
    }

    private boolean terminal(IndexJobId jobId, IndexJobPhase phase, Optional<IndexFailureCategory> category) {
        Document update = new Document("$set", new Document(ACTIVE, false).append("phase", phase.name()));
        category.ifPresent(value -> update.get("$set", Document.class).append("failureCategory", value.name()));
        return template.getCollection(IndexCollections.INDEX_JOBS).updateOne(new Document(JOB_ID, jobId.value()).append(ACTIVE, true)
                .append("phase", IndexJobPhase.RUNNING.name()), update).getModifiedCount() == 1L;
    }

    @Override
    public void reconcileCommittedJobs() {
        for (Document repository : template.getCollection(IndexCollections.REPOSITORIES).find()) {
            reconcileCommitted(RepositoryId.of(repository.getString(REPOSITORY_ID)));
        }
        reconcileReadyReviews();
        reconcileReadyGitJobs();
    }

    @Override
    public void failUnreconciledRunningJobs() {
        reconcileReadyGitJobs();
        reconcileReadyReviews();
        for (Document reviewJob : template.getCollection(IndexCollections.INDEX_JOBS).find(new Document(ACTIVE, true)
                .append("phase", IndexJobPhase.RUNNING.name()).append("operation", IndexJobOperation.REVIEW.name()))) {
            String ownerJobId = reviewJob.getString(JOB_ID);
            template.getCollection(IndexCollections.REVIEW_MANIFESTS).updateMany(new Document(REPOSITORY_ID, reviewJob.getString(REPOSITORY_ID))
                            .append("ownerJobId", ownerJobId).append("state", "PREPARING"),
                    Updates.combine(Updates.set("state", "FAILED"), Updates.set("failureCategory", IndexFailureCategory.WORKER_INTERRUPTED.name())));
            template.getCollection(IndexCollections.GIT_EVIDENCE_MANIFESTS).updateMany(new Document(REPOSITORY_ID, reviewJob.getString(REPOSITORY_ID))
                    .append("ownerJobId", ownerJobId).append("state", "PREPARING"), Updates.set("state", "FAILED"));
        }
        template.getCollection(IndexCollections.INDEX_JOBS).updateMany(new Document(ACTIVE, true).append("phase", IndexJobPhase.RUNNING.name()),
                new Document("$set", new Document(ACTIVE, false).append("phase", IndexJobPhase.FAILED.name())
                        .append("failureCategory", IndexFailureCategory.WORKER_INTERRUPTED.name())));
    }

    @Override
    public Optional<IndexJob> reconcileCommitted(RepositoryId repositoryId) {
        Document repository = template.getCollection(IndexCollections.REPOSITORIES)
                .find(new Document(REPOSITORY_ID, repositoryId.value()).append("currentPointer.committedJobId", new Document("$exists", true))).first();
        if (Objects.isNull(repository)) {
            return Optional.empty();
        }
        Document currentPointer = Objects.requireNonNull(repository.get("currentPointer", Document.class), "current pointer is required");
        String committedJobId = currentPointer.getString("committedJobId");
        Document candidate = activeRunningJob(repositoryId, committedJobId);
        if (Objects.isNull(candidate) || !matchesCommittedResult(repository, candidate)) {
            return Optional.empty();
        }
        Document completed = template.getCollection(IndexCollections.INDEX_JOBS).findOneAndUpdate(activeRunningFilter(repositoryId, committedJobId),
                new Document("$set", new Document(ACTIVE, false).append("phase", IndexJobPhase.COMPLETE.name())),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        return Optional.ofNullable(completed).map(this::from);
    }

    @Override
    public Optional<RepositoryRevision> currentRevision(RepositoryId repositoryId) {
        return publicationState(repositoryId).flatMap(IndexPublicationState::currentPointer).map(PublishedGenerationPointer::revision);
    }

    @Override
    public Optional<IndexPublicationState> publicationState(RepositoryId repositoryId) {
        Document repository = template.getCollection(IndexCollections.REPOSITORIES).find(new Document(REPOSITORY_ID, repositoryId.value())).first();
        if (Objects.isNull(repository)) {
            return Optional.empty();
        }
        return Optional.of(new IndexPublicationState(pointerFromRepository(repository), Optional.ofNullable(repository.get("rollbackPointer", Document.class))
                .map(MongoIndexJobStore::pointerFrom)));
    }

    @Override
    public Optional<RollbackGenerationCommand> rollbackCommand(IndexJob job) {
        if (job.operation() != IndexJobOperation.ROLLBACK || job.target().isEmpty()) {
            return Optional.empty();
        }
        Document document = activeRunning(job);
        if (Objects.isNull(document)) {
            return Optional.empty();
        }
        Document current = document.get("expectedCurrent", Document.class);
        Document rollback = document.get("expectedRollback", Document.class);
        if (Objects.isNull(current) || Objects.isNull(rollback)) {
            return Optional.empty();
        }
        return Optional.of(new RollbackGenerationCommand(job.repositoryId(), pointerFrom(current), pointerFrom(rollback), job.id().value()));
    }

    @Override
    public Optional<IndexPublicationIntent> prepareBuildPublication(IndexJob job, ManifestDigest sealedManifestDigest) {
        if (job.operation() != IndexJobOperation.BUILD || job.target().isEmpty() || Objects.isNull(activeRunning(job))) {
            return Optional.empty();
        }
        IndexJobTarget target = job.target().orElseThrow();
        Document stored = activeRunning(job);
        Document persisted = stored.get("publicationIntent", Document.class);
        if (Objects.nonNull(persisted)) {
            return Optional.of(intentFrom(persisted));
        }
        Optional<PublishedGenerationPointer> parent = Optional.ofNullable(stored.get("expectedParent", Document.class)).map(MongoIndexJobStore::pointerFrom);
        IndexPublicationIntent intent = new IndexPublicationIntent(job.id(), IndexJobOperation.BUILD, job.repositoryId(), target.revision(),
                target.generationId(), sealedManifestDigest, parent, Optional.empty(), Optional.empty());
        long updated = template.getCollection(IndexCollections.INDEX_JOBS).updateOne(activeRunningFilter(job.repositoryId(), job.id().value())
                .append("publicationIntent", new Document("$exists", false)), new Document("$set", new Document("publicationIntent", intentDocument(intent)))).getModifiedCount();
        if (updated == 1L) {
            return Optional.of(intent);
        }
        Document refreshed = activeRunning(job);
        if (Objects.isNull(refreshed)) {
            return Optional.empty();
        }
        return Optional.ofNullable(refreshed.get("publicationIntent", Document.class)).map(MongoIndexJobStore::intentFrom);
    }

    @Override
    public boolean gitEvidenceReady(IndexJob job) {
        if (job.operation() != IndexJobOperation.GIT_REFS && job.operation() != IndexJobOperation.GIT_HISTORY
                && job.operation() != IndexJobOperation.GIT_COMPARISON) { return false; }
        Document document = template.getCollection(IndexCollections.INDEX_JOBS).find(new Document(JOB_ID, job.id().value())
                .append(REPOSITORY_ID, job.repositoryId().value())).first();
        if (Objects.isNull(document)) { return false; }
        Document payload = document.get("gitEvidence", Document.class);
        if (Objects.isNull(payload) || Objects.isNull(payload.getString("evidenceId"))) { return false; }
        Document manifest = template.getCollection(IndexCollections.GIT_EVIDENCE_MANIFESTS).find(new Document(REPOSITORY_ID, job.repositoryId().value())
                .append("evidenceId", payload.getString("evidenceId")).append("ownerJobId", job.id().value()).append("state", "READY")).first();
        if (Objects.isNull(manifest)) { return false; }
        if (job.operation() != IndexJobOperation.GIT_COMPARISON) { return true; }
        String previous = payload.getString("previousRevision");
        String current = payload.getString("revision");
        String previousSnapshotId = manifest.getString("previousSnapshotId");
        String currentSnapshotId = manifest.getString("currentSnapshotId");
        if (!"COMPARISON".equals(manifest.getString("kind")) || Objects.isNull(previous) || Objects.isNull(current)
                || !previous.equals(manifest.getString("previous")) || !current.equals(manifest.getString("current"))
                || Objects.isNull(previousSnapshotId) || Objects.isNull(currentSnapshotId)) {
            return false;
        }
        if (!previousSnapshotId.equals(payload.getString("previousSnapshotId")) || !currentSnapshotId.equals(payload.getString("currentSnapshotId"))) {
            return false;
        }
        long readySnapshots = template.getCollection(IndexCollections.GIT_EVIDENCE_MANIFESTS).countDocuments(new Document(REPOSITORY_ID, job.repositoryId().value())
                .append("kind", "SNAPSHOT").append("state", "READY").append("evidenceId", new Document("$in", List.of(previousSnapshotId, currentSnapshotId))));
        return readySnapshots == 2L;
    }

    @Override
    public boolean reviewReady(IndexJob job) {
        if (job.operation() != IndexJobOperation.REVIEW) {
            return false;
        }
        Document document = template.getCollection(IndexCollections.INDEX_JOBS).find(new Document(JOB_ID, job.id().value())
                .append(REPOSITORY_ID, job.repositoryId().value()).append(ACTIVE, true).append("phase", IndexJobPhase.RUNNING.name())
                .append("operation", IndexJobOperation.REVIEW.name()).append("review.stage", ReviewPreparationStage.READY.name())).first();
        return Objects.nonNull(document) && reviewManifestReady(document);
    }

    private IndexJob insertBuild(RepositoryId repositoryId, RepositoryRevision revision, boolean rebuild,
                                 Optional<PublishedGenerationPointer> expectedParent) {
        IndexJobId jobId = IndexJobId.create();
        IndexJobTarget target = new IndexJobTarget(revision, new GenerationId("g-" + jobId.value().replace("-", "")), nextGeneration(repositoryId));
        Document job = targetDocument(jobId, repositoryId, target, IndexJobOperation.BUILD, rebuild);
        expectedParent.ifPresent(pointer -> job.append("expectedParent", pointerDocument(pointer)));
        insert(job, repositoryId);
        return from(job);
    }

    private IndexJob insertGitJob(RepositoryId repositoryId, IndexJobOperation operation, GitEvidenceJob payload) {
        Objects.requireNonNull(repositoryId, "repository id is required");
        IndexJobId jobId = IndexJobId.create();
        Document job = new Document(JOB_ID, jobId.value()).append(REPOSITORY_ID, repositoryId.value()).append(ACTIVE, true)
                .append("phase", IndexJobPhase.ACCEPTED.name()).append("operation", operation.name()).append("rebuild", false)
                .append("jobVersion", IndexSchemaContract.PERSISTED_JOB_VERSION)
                .append("gitEvidence", gitEvidenceDocument(payload)).append("createdAt", new Date());
        insert(job, repositoryId);
        return from(job);
    }

    private void reconcileReadyGitJobs() {
        for (Document job : template.getCollection(IndexCollections.INDEX_JOBS).find(new Document(ACTIVE, true)
                .append("phase", IndexJobPhase.RUNNING.name()).append("operation", new Document("$in", List.of(IndexJobOperation.GIT_REFS.name(), IndexJobOperation.GIT_HISTORY.name(), IndexJobOperation.GIT_COMPARISON.name()))))) {
            Document payload = job.get("gitEvidence", Document.class);
            if (Objects.isNull(payload)) { continue; }
            String evidenceId = payload.getString("evidenceId");
            if (Objects.isNull(evidenceId)) { continue; }
            if (gitEvidenceReady(from(job))) {
                terminal(new IndexJobId(job.getString(JOB_ID)), IndexJobPhase.COMPLETE, Optional.empty());
            }
        }
    }

    private void reconcileReadyReviews() {
        for (Document job : template.getCollection(IndexCollections.INDEX_JOBS).find(new Document(ACTIVE, true)
                .append("phase", IndexJobPhase.RUNNING.name()).append("operation", IndexJobOperation.REVIEW.name())
                .append("review.stage", ReviewPreparationStage.READY.name()))) {
            if (reviewManifestReady(job)) {
                terminal(new IndexJobId(job.getString(JOB_ID)), IndexJobPhase.COMPLETE, Optional.empty());
            }
        }
    }
    private boolean reviewManifestReady(Document job) {
        Document payload = job.get("review", Document.class);
        if (Objects.isNull(payload)) {
            return false;
        }
        Document manifest = template.getCollection(IndexCollections.REVIEW_MANIFESTS).find(new Document(REPOSITORY_ID, job.getString(REPOSITORY_ID))
                .append("reviewId", payload.getString("reviewId")).append("ownerJobId", job.getString(JOB_ID)).append("state", "READY")
                .append("comparisonId", payload.getString("comparisonId")).append("a.snapshotId", payload.getString("previousSnapshotId"))
                .append("b.snapshotId", payload.getString("currentSnapshotId"))).first();
        if (Objects.isNull(manifest) || Objects.isNull(payload.get("a", Document.class))
                || Objects.isNull(payload.get("b", Document.class)) || Objects.isNull(payload.getString("comparisonId"))
                || Objects.isNull(payload.getString("previousSnapshotId")) || Objects.isNull(payload.getString("currentSnapshotId"))) {
            return false;
        }
        try {
            new com.java.semantic.indexer.review.ReviewReadinessValidator(template).validateReadyCandidate(from(job));
            return true;
        } catch (com.java.semantic.indexer.review.ReviewPreparationException exception) {
            return false;
        }
    }

    private IndexJob insertNoWork(RepositoryId repositoryId) {
        IndexJobId jobId = IndexJobId.create();
        Document job = new Document(JOB_ID, jobId.value()).append(REPOSITORY_ID, repositoryId.value()).append(ACTIVE, false)
                .append("phase", IndexJobPhase.COMPLETE.name()).append("operation", IndexJobOperation.NO_WORK.name())
                .append("jobVersion", IndexSchemaContract.PERSISTED_JOB_VERSION).append("createdAt", new Date());
        template.getCollection(IndexCollections.INDEX_JOBS).insertOne(job);
        return from(job);
    }

    private static Document targetDocument(IndexJobId jobId, RepositoryId repositoryId, IndexJobTarget target,
                                           IndexJobOperation operation, boolean rebuild) {
        return new Document(JOB_ID, jobId.value()).append(REPOSITORY_ID, repositoryId.value()).append("target", targetDocument(target)).append(ACTIVE, true)
                .append("phase", IndexJobPhase.ACCEPTED.name()).append("operation", operation.name()).append("rebuild", rebuild)
                .append("generationHighWatermark", target.generation()).append("jobVersion", IndexSchemaContract.PERSISTED_JOB_VERSION)
                .append("createdAt", new Date());
    }

    private static Document gitEvidenceDocument(GitEvidenceJob payload) {
        Document document = new Document();
        payload.catalogId().ifPresent(value -> document.append("catalogId", value.value()));
        payload.branch().ifPresent(value -> document.append("branch", value));
        payload.revision().ifPresent(value -> document.append("revision", value.value()));
        payload.previousRevision().ifPresent(value -> document.append("previousRevision", value.value()));
        payload.previousSnapshotId().ifPresent(value -> document.append("previousSnapshotId", value.value()));
        payload.currentSnapshotId().ifPresent(value -> document.append("currentSnapshotId", value.value()));
        return document;
    }

    private static Document targetDocument(IndexJobTarget target) {
        return new Document("revision", target.revision().value()).append("generationId", target.generationId().value())
                .append("generation", target.generation());
    }

    private void insert(Document job, RepositoryId repositoryId) {
        try {
            template.getCollection(IndexCollections.INDEX_JOBS).insertOne(job);
        } catch (DuplicateKeyException exception) {
            throw new IndexJobAlreadyActiveException(repositoryId);
        } catch (MongoWriteException exception) {
            if (exception.getError().getCategory() == ErrorCategory.DUPLICATE_KEY) {
                throw new IndexJobAlreadyActiveException(repositoryId);
            }
            throw exception;
        }
    }

    private long nextGeneration(RepositoryId repositoryId) {
        Document maximum = template.getCollection(IndexCollections.INDEX_JOBS).find(new Document(REPOSITORY_ID, repositoryId.value())
                        .append("generationHighWatermark", new Document("$exists", true)))
                .sort(new Document("generationHighWatermark", -1)).limit(1).first();
        if (Objects.isNull(maximum)) {
            return 1L;
        }
        Number generation = maximum.get("generationHighWatermark", Number.class);
        if (Objects.isNull(generation)) {
            throw new IllegalStateException("persisted job has no generation high-watermark");
        }
        return generation.longValue() + 1L;
    }

    private static boolean hasRequiredProjections(Document manifest) {
        Object values = manifest.get("projectionVersions");
        if (!(values instanceof List<?> list)) {
            return false;
        }
        Map<String, Integer> actual = new HashMap<>();
        for (Object value : list) {
            if (!(value instanceof Document document)) {
                return false;
            }
            String name = document.getString("name");
            Integer version = document.getInteger("version");
            if (Objects.isNull(name) || Objects.isNull(version)) {
                return false;
            }
            actual.put(name, version);
        }
        return IndexSchemaContract.requiredProjectionVersions().equals(Map.copyOf(actual));
    }

    private boolean sealed(RepositoryId repositoryId, PublishedGenerationPointer pointer) {
        Document manifest = template.getCollection(IndexCollections.GENERATION_MANIFESTS).find(new Document(REPOSITORY_ID, repositoryId.value())
                .append("generationId", pointer.generationId().value()).append("sourceRevision", pointer.revision().value())
                .append("identityDigest", pointer.manifestDigest().value()).append("writeState", "SEALED_VALID")).first();
        return Objects.nonNull(manifest);
    }

    private Document activeRunning(IndexJob job) {
        return template.getCollection(IndexCollections.INDEX_JOBS).find(new Document(JOB_ID, job.id().value()).append(REPOSITORY_ID, job.repositoryId().value())
                .append(ACTIVE, true).append("phase", IndexJobPhase.RUNNING.name())).first();
    }

    private Optional<Document> findRepository(RepositoryId repositoryId, PublishedGenerationPointer current, Optional<PublishedGenerationPointer> rollback) {
        Document filter = pointerMatch(new Document(REPOSITORY_ID, repositoryId.value()), "currentPointer.", current);
        rollback.ifPresent(pointer -> pointerMatch(filter, "rollbackPointer.", pointer));
        return Optional.ofNullable(template.getCollection(IndexCollections.REPOSITORIES).find(filter).first());
    }

    private static Document pointerMatch(Document filter, String prefix, PublishedGenerationPointer pointer) {
        return filter.append(prefix + "revision", pointer.revision().value()).append(prefix + "generationId", pointer.generationId().value())
                .append(prefix + "manifestDigest", pointer.manifestDigest().value()).append(prefix + "committedJobId", pointer.committedJobId())
                .append(prefix + "publishedAt", Date.from(pointer.publishedAt()));
    }

    private static Document pointerDocument(PublishedGenerationPointer pointer) {
        return new Document("revision", pointer.revision().value()).append("generationId", pointer.generationId().value())
                .append("manifestDigest", pointer.manifestDigest().value()).append("committedJobId", pointer.committedJobId()).append("publishedAt", Date.from(pointer.publishedAt()));
    }

    private static Optional<PublishedGenerationPointer> pointerFromRepository(Document document) {
        return Optional.ofNullable(document.get("currentPointer", Document.class)).map(MongoIndexJobStore::pointerFrom);
    }

    private static PublishedGenerationPointer pointerFrom(Document document) {
        return new PublishedGenerationPointer(new RepositoryRevision(document.getString("revision")), new GenerationId(document.getString("generationId")),
                new ManifestDigest(document.getString("manifestDigest")), document.getString("committedJobId"), document.getDate("publishedAt").toInstant());
    }

    private Document activeRunningJob(RepositoryId repositoryId, String jobId) {
        return template.getCollection(IndexCollections.INDEX_JOBS).find(activeRunningFilter(repositoryId, jobId)).first();
    }

    private static Document activeRunningFilter(RepositoryId repositoryId, String jobId) {
        return new Document(JOB_ID, jobId).append(REPOSITORY_ID, repositoryId.value()).append(ACTIVE, true)
                .append("phase", IndexJobPhase.RUNNING.name()).append("operation", new Document("$in", List.of(IndexJobOperation.BUILD.name(), IndexJobOperation.ROLLBACK.name())));
    }

    private boolean matchesCommittedResult(Document repository, Document job) {
        Document document = job.get("publicationIntent", Document.class);
        if (Objects.isNull(document)) {
            return false;
        }
        IndexPublicationIntent intent = intentFrom(document);
        if (intent.operation() == IndexJobOperation.ROLLBACK) {
            return currentPointerMatches(repository, intent.targetRevision(), intent.targetGenerationId(), intent.targetManifestDigest(), intent.jobId().value())
                    && rollbackPointerMatches(repository, intent.expectedCurrent().orElseThrow());
        }
        if (intent.operation() == IndexJobOperation.BUILD) {
            return currentPointerMatches(repository, intent.targetRevision(), intent.targetGenerationId(), intent.targetManifestDigest(), intent.jobId().value())
                    && sealedManifestOwnedBy(intent) && expectedParentBecameRollback(repository, intent.expectedParent());
        }
        return false;
    }

    private boolean sealedManifestOwnedBy(IndexPublicationIntent intent) {
        Document manifest = template.getCollection(IndexCollections.GENERATION_MANIFESTS).find(new Document(REPOSITORY_ID, intent.repositoryId().value())
                .append("sourceRevision", intent.targetRevision().value()).append("generationId", intent.targetGenerationId().value())
                .append("identityDigest", intent.targetManifestDigest().value()).append("ownerJobId", intent.jobId().value())
                .append("writeState", "SEALED_VALID")).first();
        return Objects.nonNull(manifest);
    }

    private static boolean pointerMatches(Document document, RepositoryRevision revision, GenerationId generationId,
                                          ManifestDigest digest, String committedJobId) {
        return revision.value().equals(document.getString("revision")) && generationId.value().equals(document.getString("generationId"))
                && digest.value().equals(document.getString("manifestDigest")) && committedJobId.equals(document.getString("committedJobId"));
    }

    private static boolean currentPointerMatches(Document repository, RepositoryRevision revision, GenerationId generationId,
                                                 ManifestDigest digest, String committedJobId) {
        Document current = repository.get("currentPointer", Document.class);
        return Objects.nonNull(current) && pointerMatches(current, revision, generationId, digest, committedJobId);
    }

    private static boolean expectedParentBecameRollback(Document repository, Optional<PublishedGenerationPointer> expectedParent) {
        if (expectedParent.isEmpty()) {
            return !repository.containsKey("rollbackPointer");
        }
        Document rollback = repository.get("rollbackPointer", Document.class);
        if (Objects.isNull(rollback)) {
            return false;
        }
        PublishedGenerationPointer pointer = expectedParent.orElseThrow();
        return pointerMatches(rollback, pointer.revision(), pointer.generationId(), pointer.manifestDigest(), pointer.committedJobId())
                && pointer.publishedAt().equals(rollback.getDate("publishedAt").toInstant());
    }

    private static boolean rollbackPointerMatches(Document repository, PublishedGenerationPointer pointer) {
        Document rollback = repository.get("rollbackPointer", Document.class);
        if (Objects.isNull(rollback)) {
            return false;
        }
        return pointerMatches(rollback, pointer.revision(), pointer.generationId(), pointer.manifestDigest(), pointer.committedJobId())
                && pointer.publishedAt().equals(rollback.getDate("publishedAt").toInstant());
    }

    private static Document intentDocument(IndexPublicationIntent intent) {
        Document document = new Document("jobId", intent.jobId().value()).append("operation", intent.operation().name())
                .append(REPOSITORY_ID, intent.repositoryId().value()).append("targetRevision", intent.targetRevision().value())
                .append("targetGenerationId", intent.targetGenerationId().value()).append("targetManifestDigest", intent.targetManifestDigest().value());
        intent.expectedParent().ifPresent(pointer -> document.append("expectedParent", pointerDocument(pointer)));
        intent.expectedCurrent().ifPresent(pointer -> document.append("expectedCurrent", pointerDocument(pointer)));
        intent.expectedRollback().ifPresent(pointer -> document.append("expectedRollback", pointerDocument(pointer)));
        return document;
    }

    private static IndexPublicationIntent intentFrom(Document document) {
        Optional<PublishedGenerationPointer> parent = Optional.ofNullable(document.get("expectedParent", Document.class)).map(MongoIndexJobStore::pointerFrom);
        Optional<PublishedGenerationPointer> current = Optional.ofNullable(document.get("expectedCurrent", Document.class)).map(MongoIndexJobStore::pointerFrom);
        Optional<PublishedGenerationPointer> rollback = Optional.ofNullable(document.get("expectedRollback", Document.class)).map(MongoIndexJobStore::pointerFrom);
        return new IndexPublicationIntent(new IndexJobId(document.getString("jobId")), IndexJobOperation.valueOf(document.getString("operation")),
                RepositoryId.of(document.getString(REPOSITORY_ID)), new RepositoryRevision(document.getString("targetRevision")),
                new GenerationId(document.getString("targetGenerationId")), new ManifestDigest(document.getString("targetManifestDigest")),
                parent, current, rollback);
    }

    private IndexJob from(Document document) {
        Number jobVersion = document.get("jobVersion", Number.class);
        if (Objects.isNull(jobVersion) || jobVersion.intValue() != IndexSchemaContract.PERSISTED_JOB_VERSION) {
            throw new IllegalArgumentException("unsupported persisted index job contract");
        }
        IndexJobOperation operation = IndexJobOperation.valueOf(document.getString("operation"));
        Optional<IndexJobTarget> target = Optional.empty();
        if (operation == IndexJobOperation.BUILD || operation == IndexJobOperation.ROLLBACK || operation == IndexJobOperation.REVIEW) {
            target = Optional.ofNullable(document.get("target", Document.class)).map(MongoIndexJobStore::targetFrom);
        }
        Optional<GitEvidenceJob> gitEvidence = Optional.empty();
        if (operation == IndexJobOperation.GIT_REFS || operation == IndexJobOperation.GIT_HISTORY || operation == IndexJobOperation.GIT_COMPARISON) {
            Document payload = Objects.requireNonNull(document.get("gitEvidence", Document.class), "git evidence payload is required");
            Optional<GitEvidenceId> catalogId = Optional.ofNullable(payload.getString("catalogId")).map(GitEvidenceId::new);
            Optional<String> branch = Optional.ofNullable(payload.getString("branch"));
            Optional<RepositoryRevision> revision = Optional.ofNullable(payload.getString("revision")).map(RepositoryRevision::new);
            Optional<GitEvidenceId> evidenceId = Optional.ofNullable(payload.getString("evidenceId")).map(GitEvidenceId::new);
            Optional<RepositoryRevision> previousRevision = Optional.ofNullable(payload.getString("previousRevision")).map(RepositoryRevision::ofSha);
            Optional<GitSnapshotId> previousSnapshotId = Optional.ofNullable(payload.getString("previousSnapshotId")).map(GitSnapshotId::new);
            Optional<GitSnapshotId> currentSnapshotId = Optional.ofNullable(payload.getString("currentSnapshotId")).map(GitSnapshotId::new);
            gitEvidence = Optional.of(new GitEvidenceJob(catalogId, branch, revision, evidenceId, previousRevision, previousSnapshotId, currentSnapshotId));
        }
        Optional<ReviewJobPayload> review = operation == IndexJobOperation.REVIEW
                ? Optional.of(reviewFrom(Objects.requireNonNull(document.get("review", Document.class), "review payload is required")))
                : Optional.empty();
        return new IndexJob(new IndexJobId(document.getString(JOB_ID)), RepositoryId.of(document.getString(REPOSITORY_ID)), target,
                IndexJobPhase.valueOf(document.getString("phase")), Boolean.TRUE.equals(document.getBoolean(ACTIVE)),
                Optional.ofNullable(document.getString("failureCategory")).map(IndexFailureCategory::valueOf), Boolean.TRUE.equals(document.getBoolean("rebuild")),
                operation, gitEvidence, review);
    }

    private ReviewJobPayload reviewFrom(Document payload) {
        Document baselineDocument = Objects.requireNonNull(payload.get("baseline", Document.class), "review baseline is required");
        CapturedReviewBaseline baseline = new CapturedReviewBaseline(pointerFrom(Objects.requireNonNull(baselineDocument.get("pointer", Document.class),
                "review baseline pointer is required")), Objects.requireNonNull(baselineDocument.getDate("capturedAt"), "review baseline time is required").toInstant());
        Document reserved = Objects.requireNonNull(payload.get("reservedTargets", Document.class), "reserved review targets are required");
        Optional<SealedGeneration> a = sealedGeneration(payload, "a");
        Optional<SealedGeneration> b = sealedGeneration(payload, "b");
        return new ReviewJobPayload(new ReviewId(payload.getString("reviewId")), baseline, new RepositoryRevision(payload.getString("requestedRevision")),
                new ReviewBuildTargets(targetFrom(Objects.requireNonNull(reserved.get("a", Document.class), "reserved A target is required")),
                        targetFrom(Objects.requireNonNull(reserved.get("b", Document.class), "reserved B target is required"))),
                ReviewPreparationStage.valueOf(payload.getString("stage")), a, b,
                Optional.ofNullable(payload.getString("comparisonId")).map(GitComparisonId::new),
                Optional.ofNullable(payload.getString("previousSnapshotId")).map(GitSnapshotId::new),
                Optional.ofNullable(payload.getString("currentSnapshotId")).map(GitSnapshotId::new));
    }

    private Optional<SealedGeneration> sealedGeneration(Document payload, String side) {
        return Optional.ofNullable(payload.get(side, Document.class)).map(document -> template.getConverter().read(SealedGeneration.class, document));
    }

    private void requireCompatibleReviewGeneration(Document job, SealedGeneration generation, IndexJobTarget target) {
        Document manifest = template.getCollection(IndexCollections.GENERATION_MANIFESTS).find(new Document(REPOSITORY_ID, job.getString(REPOSITORY_ID))
                .append("generationId", generation.selected().generationId().value()).append("sourceRevision", target.revision().value())
                .append("identityDigest", generation.selected().manifestDigest().value()).append("writeState", "SEALED_VALID")).first();
        if (Objects.isNull(manifest) || !hasRequiredProjections(manifest)
                || Objects.isNull(manifest.getString("analysisFingerprint")) || Objects.isNull(manifest.get("analysisEvidence", Document.class))) {
            throw new IllegalStateException("review side requires a compatible sealed generation");
        }
        String owner = manifest.getString("ownerJobId");
        if (generation.selected().generationId().equals(target.generationId()) && !job.getString(JOB_ID).equals(owner)) {
            throw new IllegalStateException("reserved review generation has a different owner");
        }
    }

    private static Document noBatches(String field) {
        return new Document("$eq", List.of(new Document("$size", new Document("$ifNull", List.of("$" + field, List.of()))), 0));
    }

    private static IndexJobTarget targetFrom(Document document) {
        return new IndexJobTarget(new RepositoryRevision(document.getString("revision")), new GenerationId(document.getString("generationId")),
                Objects.requireNonNull(document.get("generation", Number.class), "target generation is required").longValue());
    }
}
