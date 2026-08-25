package com.java.semantic.indexer.job;

import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.index.IndexCollections;
import com.java.semantic.model.index.RepositoryFence;
import com.java.semantic.model.index.IndexSchemaContract;
import com.java.semantic.model.index.ManifestDigest;
import com.java.semantic.model.index.PublishedGenerationPointer;
import com.java.semantic.model.index.RollbackGenerationCommand;
import com.java.semantic.indexer.store.PublicationConflictException;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.mongodb.DuplicateKeyException;
import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
import com.mongodb.client.result.UpdateResult;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.AggregationUpdate;
import org.springframework.data.mongodb.core.query.BasicQuery;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Date;
import java.util.Objects;
import java.util.Optional;

/** Mongo coordination without transactions: repository fencing is always claimed before a worker proceeds. */
@Component
public final class MongoIndexJobStore implements IndexJobStore {

    private static final String JOB_ID = "jobId";
    private static final String REPO_ID = "repoId";
    private static final String ACTIVE = "active";
    private final MongoTemplate template;
    private final ClaimJobDocumentTransition claimJobDocumentTransition;

    @Autowired
    public MongoIndexJobStore(MongoTemplate template) {
        this(template, MongoIndexJobStore::claimJobDocument);
    }

    MongoIndexJobStore(MongoTemplate template, ClaimJobDocumentTransition claimJobDocumentTransition) {
        this.template = Objects.requireNonNull(template, "mongo template is required");
        this.claimJobDocumentTransition = Objects.requireNonNull(claimJobDocumentTransition,
                "claim job document transition is required");
    }

    @Override
    public IndexJob admit(RepositoryId repositoryId, RepositoryRevision revision, boolean rebuild) {
        IndexJobId jobId = IndexJobId.create();
        long generation = nextGeneration(repositoryId);
        GenerationId generationId = new GenerationId("g-" + jobId.value().replace("-", ""));
        Document document = new Document(JOB_ID, jobId.value())
                .append(REPO_ID, repositoryId.value())
                .append("revision", revision.value())
                .append("generationId", generationId.value())
                .append("generation", generation)
                .append("phase", IndexJobPhase.ACCEPTED.name())
                .append(ACTIVE, true)
                .append("rebuild", rebuild)
                .append("operation", IndexJobOperation.BUILD.name())
                .append("createdAt", new Date());
        try {
            template.getCollection(IndexCollections.INDEX_JOBS).insertOne(document);
        } catch (DuplicateKeyException exception) {
            throw new IndexJobAlreadyActiveException(repositoryId);
        } catch (MongoWriteException exception) {
            throwAdmissionFailure(repositoryId, exception);
        }
        return from(document);
    }

    @Override
    public IndexJob admitEnsure(RepositoryId repositoryId, RepositoryRevision revision) {
        Document repository = template.getCollection(IndexCollections.REPOSITORIES)
                .find(new Document(REPO_ID, repositoryId.value())).first();
        if (Objects.isNull(repository)) {
            return admit(repositoryId, revision, false);
        }
        Document manifest = template.getCollection(IndexCollections.GENERATION_MANIFESTS).find(new Document(REPO_ID, repositoryId.value())
                .append("sourceRevision", repository.getString("revision")).append("generationId", repository.getString("generationId"))
                .append("identityDigest", repository.getString("manifestDigest")).append("writeState", "SEALED_VALID")).first();
        if (Objects.isNull(manifest)) {
            return admit(repositoryId, revision, false);
        }
        Number schemaVersion = manifest.get("schemaVersion", Number.class);
        if (Objects.isNull(schemaVersion) || schemaVersion.intValue() != IndexSchemaContract.SCHEMA_VERSION) {
            throw new IndexSchemaRebuildRequiredException();
        }
        if (!revision.value().equals(repository.getString("revision"))) {
            return admit(repositoryId, revision, false);
        }
        if (!hasRequiredProjections(manifest)) {
            return admit(repositoryId, revision, true);
        }
        return insertNoWork(repositoryId, revision, repository.getString("generationId"));
    }

    @Override
    public IndexJob admitRollback(RepositoryId repositoryId, PublishedGenerationPointer expectedCurrent,
                                  PublishedGenerationPointer expectedRollback) {
        Document repository = template.getCollection(IndexCollections.REPOSITORIES).find(repositoryPointerFilter(
                repositoryId, expectedCurrent, expectedRollback)).first();
        if (Objects.isNull(repository) || !sealed(repositoryId, expectedRollback)) {
            throw new PublicationConflictException();
        }
        IndexJobId jobId = IndexJobId.create();
        Document document = new Document(JOB_ID, jobId.value()).append(REPO_ID, repositoryId.value())
                .append("revision", expectedRollback.revision().value()).append("generationId", expectedRollback.generationId().value())
                .append("generation", nextGeneration(repositoryId)).append("phase", IndexJobPhase.ACCEPTED.name())
                .append(ACTIVE, true).append("operation", IndexJobOperation.ROLLBACK.name())
                .append("expectedCurrent", pointerDocument(expectedCurrent)).append("expectedRollback", pointerDocument(expectedRollback))
                .append("publicationIntent", rollbackIntentDocument(jobId, repositoryId, expectedCurrent, expectedRollback))
                .append("createdAt", new Date());
        try {
            template.getCollection(IndexCollections.INDEX_JOBS).insertOne(document);
        } catch (DuplicateKeyException exception) {
            throw new IndexJobAlreadyActiveException(repositoryId);
        } catch (MongoWriteException exception) {
            throwAdmissionFailure(repositoryId, exception);
        }
        return from(document);
    }

    @Override
    public Optional<IndexJob> find(IndexJobId jobId) {
        Document document = template.getCollection(IndexCollections.INDEX_JOBS)
                .find(new Document(JOB_ID, jobId.value())).first();
        return Optional.ofNullable(document).map(MongoIndexJobStore::from);
    }

    @Override
    public Optional<IndexJob> claim(IndexJobId jobId, String workerId, Duration claimLifetime) {
        Objects.requireNonNull(jobId, "job id is required");
        Objects.requireNonNull(workerId, "worker id is required");
        requirePositive(claimLifetime, "claim lifetime");
        IndexJob job = find(jobId).orElse(null); // cs-allow
        if (Objects.isNull(job) || !job.active()) {
            return Optional.empty();
        }
        AggregationUpdate claimUpdate = AggregationUpdate.update()
                .set("fence").toValue(new Document("$add", java.util.List.of(
                        new Document("$ifNull", java.util.List.of("$fence", 0)), 1)))
                .set("activeJobId").toValue(job.id().value())
                .set("activeWorkerId").toValue(workerId)
                .set("activeGenerationId").toValue(job.generationId().value())
                .set("claimUntil").toValue(serverExpiry(claimLifetime));
        Document repository;
        try {
            repository = template.findAndModify(
                    new BasicQuery(new Document(REPO_ID, job.repositoryId().value())
                            .append("activeJobId", new Document("$exists", false))),
                    claimUpdate, FindAndModifyOptions.options().upsert(true).returnNew(true), Document.class, IndexCollections.REPOSITORIES);
        } catch (DuplicateKeyException | MongoWriteException | org.springframework.dao.DuplicateKeyException exception) {
            return Optional.empty();
        }
        if (Objects.isNull(repository)) {
            return Optional.empty();
        }
        Number fenceValue = repository.get("fence", Number.class);
        if (Objects.isNull(fenceValue)) {
            releaseRepositoryClaimWithoutFence(job, workerId);
            return Optional.empty();
        }
        Date serverClaimUntil = repository.getDate("claimUntil");
        if (Objects.isNull(serverClaimUntil)) {
            releaseExactRepositoryClaim(job, workerId, fenceValue.longValue());
            return Optional.empty();
        }
        Document claimed;
        try {
            claimed = claimJobDocumentTransition.claim(template, job, workerId, fenceValue.longValue(), serverClaimUntil);
        } catch (RuntimeException exception) {
            releaseExactRepositoryClaim(job, workerId, fenceValue.longValue());
            throw exception;
        }
        if (Objects.isNull(claimed)) {
            releaseExactRepositoryClaim(job, workerId, fenceValue.longValue());
            return Optional.empty();
        }
        if (!captureBuildParent(job, workerId, fenceValue.longValue(), repository)) {
            releaseExactRepositoryClaim(job, workerId, fenceValue.longValue());
            return Optional.empty();
        }
        return Optional.of(claimed).map(MongoIndexJobStore::from);
    }

    @Override
    public boolean renew(IndexJob job, Duration claimLifetime) {
        Objects.requireNonNull(job, "job is required");
        requirePositive(claimLifetime, "claim lifetime");
        String workerId = job.workerId().orElseThrow();
        long fence = job.fence().orElseThrow().value();
        Document result = template.findAndModify(new BasicQuery(repositoryClaim(job, workerId, fence)
                        .append("$expr", new Document("$gt", java.util.List.of("$claimUntil", "$$NOW")))),
                AggregationUpdate.update().set("claimUntil").toValue(serverExpiry(claimLifetime)),
                FindAndModifyOptions.options().returnNew(true),
                Document.class, IndexCollections.REPOSITORIES);
        if (Objects.isNull(result)) {
            return false;
        }
        Date serverClaimUntil = result.getDate("claimUntil");
        UpdateResult jobUpdate = template.updateFirst(
                new BasicQuery(jobOwnership(job, workerId, fence)), new Update().set("claimUntil", serverClaimUntil),
                IndexCollections.INDEX_JOBS);
        if (jobUpdate.getModifiedCount() == 1 && mirrorManifestExpiry(job, workerId, fence, serverClaimUntil)) {
            return true;
        }
        if (revoke(job)) {
            failAfterRevocation(job, IndexFailureCategory.WORKER_INTERRUPTED);
        } else {
            reconcileCommitted(job.repositoryId());
        }
        return false;
    }

    @Override
    public boolean revoke(IndexJob job) {
        String workerId = job.workerId().orElseThrow();
        long fence = job.fence().orElseThrow().value();
        Document result = template.findAndModify(new BasicQuery(repositoryClaim(job, workerId, fence)),
                new Update().unset("activeJobId").unset("activeWorkerId").unset("activeGenerationId").unset("claimUntil"),
                FindAndModifyOptions.options().returnNew(true), Document.class, IndexCollections.REPOSITORIES);
        return Objects.nonNull(result);
    }

    @Override
    public boolean failAfterRevocation(IndexJob job, IndexFailureCategory category) {
        Objects.requireNonNull(category, "failure category is required");
        String workerId = job.workerId().orElseThrow();
        long fence = job.fence().orElseThrow().value();
        UpdateResult updated = template.updateFirst(new BasicQuery(jobOwnership(job, workerId, fence).append("operation", job.operation().name())),
                new Update().set(ACTIVE, false).set("phase", IndexJobPhase.FAILED.name())
                        .set("failureCategory", category.name()), IndexCollections.INDEX_JOBS);
        return updated.getModifiedCount() == 1;
    }

    @Override
    public void failExpiredClaims() {
        Query query = new BasicQuery(new Document("activeJobId", new Document("$exists", true))
                .append("$expr", new Document("$lte", java.util.List.of("$claimUntil", "$$NOW"))));
        java.util.List<Document> expiredClaims = template.find(query, Document.class, IndexCollections.REPOSITORIES);
        java.util.List<IndexJob> expired = expiredClaims.stream().map(this::jobForClaim).flatMap(Optional::stream).toList();
        for (IndexJob job : expired) {
            if (revoke(job)) {
                failAfterRevocation(job, IndexFailureCategory.WORKER_INTERRUPTED);
            }
        }
    }

    @Override
    public void reconcileCommittedJobs() {
        Query query = new BasicQuery(new Document("committedJobId", new Document("$exists", true))
                .append("activeJobId", new Document("$exists", false)));
        java.util.List<Document> repositories = template.find(query, Document.class, IndexCollections.REPOSITORIES);
        for (Document repository : repositories) {
            String repositoryId = repository.getString(REPO_ID);
            if (Objects.nonNull(repositoryId)) {
                reconcileCommitted(RepositoryId.of(repositoryId));
            }
        }
    }

    @Override
    public void recoverRevokedClaims() {
        recoverRevokedClaims(claimedJobFilter());
    }

    @Override
    public void recoverRevokedClaims(RepositoryId repositoryId) {
        Objects.requireNonNull(repositoryId, "repository id is required");
        Document filter = claimedJobFilter();
        filter.append(REPO_ID, repositoryId.value());
        recoverRevokedClaims(filter);
    }

    @Override
    public Optional<IndexJob> reconcileCommitted(RepositoryId repositoryId) {
        Document repository = template.getCollection(IndexCollections.REPOSITORIES).find(new Document(REPO_ID, repositoryId.value())
                .append("committedJobId", new Document("$exists", true)).append("activeJobId", new Document("$exists", false))).first();
        if (Objects.isNull(repository)) {
            return Optional.empty();
        }
        String committedJobId = repository.getString("committedJobId");
        Document candidate = template.getCollection(IndexCollections.INDEX_JOBS).find(new Document(JOB_ID, committedJobId)
                .append(REPO_ID, repositoryId.value()).append(ACTIVE, true)).first();
        if (Objects.isNull(candidate) || !matchesCommittedResult(repository, candidate)) {
            return Optional.empty();
        }
        Document job = template.findAndModify(new BasicQuery(new Document(JOB_ID, committedJobId).append(REPO_ID, repositoryId.value())
                        .append(ACTIVE, true)), new Update().set(ACTIVE, false).set("phase", IndexJobPhase.COMPLETE.name()),
                FindAndModifyOptions.options().returnNew(true), Document.class, IndexCollections.INDEX_JOBS);
        return Optional.ofNullable(job).map(MongoIndexJobStore::from);
    }

    @Override
    public Optional<RepositoryRevision> currentRevision(RepositoryId repositoryId) {
        Document document = template.getCollection(IndexCollections.REPOSITORIES).find(new Document(REPO_ID, repositoryId.value())
                .append("revision", new Document("$exists", true))).first();
        return Optional.ofNullable(document).map(value -> new RepositoryRevision(value.getString("revision")));
    }

    @Override
    public Optional<RollbackGenerationCommand> rollbackCommand(IndexJob job) {
        Objects.requireNonNull(job, "job is required");
        if (job.operation() != IndexJobOperation.ROLLBACK || job.workerId().isEmpty() || job.fence().isEmpty()) {
            return Optional.empty();
        }
        IndexPublicationIntent intent = publicationIntent(job.id()).orElse(null); // cs-allow
        if (Objects.isNull(intent) || intent.operation() != IndexJobOperation.ROLLBACK) {
            return Optional.empty();
        }
        Document owned = template.getCollection(IndexCollections.INDEX_JOBS).find(jobOwnership(job,
                job.workerId().orElseThrow(), job.fence().orElseThrow().value())).first();
        if (Objects.isNull(owned)) {
            return Optional.empty();
        }
        return Optional.of(new RollbackGenerationCommand(job.repositoryId(), intent.expectedCurrent().orElseThrow(),
                intent.expectedRollback().orElseThrow(), job.id().value(), job.workerId().orElseThrow(), job.fence().orElseThrow()));
    }

    @Override
    public Optional<IndexPublicationIntent> prepareBuildPublication(IndexJob job, ManifestDigest sealedManifestDigest) {
        Objects.requireNonNull(job, "job is required");
        Objects.requireNonNull(sealedManifestDigest, "sealed manifest digest is required");
        if (job.operation() != IndexJobOperation.BUILD || job.workerId().isEmpty() || job.fence().isEmpty()) {
            return Optional.empty();
        }
        Optional<IndexPublicationIntent> existing = publicationIntent(job.id());
        if (existing.isPresent()) {
            return existing;
        }
        Document repository = template.getCollection(IndexCollections.REPOSITORIES).find(repositoryClaim(job,
                job.workerId().orElseThrow(), job.fence().orElseThrow().value())).first();
        if (Objects.isNull(repository)) {
            return Optional.empty();
        }
        Document claimedJob = template.getCollection(IndexCollections.INDEX_JOBS).find(jobOwnership(job,
                job.workerId().orElseThrow(), job.fence().orElseThrow().value()).append("buildParentCaptured", true)).first();
        if (Objects.isNull(claimedJob)) {
            return Optional.empty();
        }
        Optional<PublishedGenerationPointer> parent = Optional.ofNullable(claimedJob.get("buildParent", Document.class))
                .map(MongoIndexJobStore::pointerFrom);
        IndexPublicationIntent intent = new IndexPublicationIntent(job.id(), IndexJobOperation.BUILD, job.repositoryId(), job.revision(),
                job.generationId(), sealedManifestDigest, parent, Optional.empty(), Optional.empty());
        UpdateResult saved = template.updateFirst(new BasicQuery(jobOwnership(job, job.workerId().orElseThrow(),
                        job.fence().orElseThrow().value()).append("publicationIntent", new Document("$exists", false))),
                new Update().set("publicationIntent", intentDocument(intent)), IndexCollections.INDEX_JOBS);
        if (saved.getModifiedCount() == 1) {
            return Optional.of(intent);
        }
        return publicationIntent(job.id());
    }

    @Override
    public Optional<IndexPublicationIntent> publicationIntent(IndexJobId jobId) {
        Objects.requireNonNull(jobId, "job id is required");
        Document job = template.getCollection(IndexCollections.INDEX_JOBS).find(new Document(JOB_ID, jobId.value())
                .append("publicationIntent", new Document("$exists", true))).first();
        if (Objects.isNull(job)) {
            return Optional.empty();
        }
        return Optional.of(intentFrom(job.get("publicationIntent", Document.class)));
    }

    private long nextGeneration(RepositoryId repositoryId) {
        Document document = template.findAndModify(new BasicQuery(new Document(REPO_ID, repositoryId.value())),
                new Update().inc("nextJobGeneration", 1), FindAndModifyOptions.options().upsert(true).returnNew(true),
                Document.class, IndexCollections.REPOSITORIES);
        Number value = document.get("nextJobGeneration", Number.class);
        return value.longValue();
    }

    private static void throwAdmissionFailure(RepositoryId repositoryId, MongoWriteException exception) {
        if (exception.getError().getCategory() == ErrorCategory.DUPLICATE_KEY) {
            throw new IndexJobAlreadyActiveException(repositoryId);
        }
        throw exception;
    }

    private IndexJob insertNoWork(RepositoryId repositoryId, RepositoryRevision revision, String generationId) {
        IndexJobId id = IndexJobId.create();
        Document document = new Document(JOB_ID, id.value()).append(REPO_ID, repositoryId.value()).append("revision", revision.value())
                .append("generationId", generationId).append("generation", nextGeneration(repositoryId)).append("phase", IndexJobPhase.COMPLETE.name())
                .append(ACTIVE, false).append("operation", IndexJobOperation.NO_WORK.name()).append("createdAt", new Date());
        template.getCollection(IndexCollections.INDEX_JOBS).insertOne(document);
        return from(document);
    }

    private static boolean hasRequiredProjections(Document manifest) {
        java.util.List<Document> versions = manifest.getList("projectionVersions", Document.class);
        if (Objects.isNull(versions)) {
            return false;
        }
        java.util.Map<String, Integer> actual = new java.util.LinkedHashMap<>();
        for (Document version : versions) {
            String name = version.getString("name");
            Number value = version.get("version", Number.class);
            if (Objects.isNull(name) || Objects.isNull(value)) {
                return false;
            }
            actual.put(name, value.intValue());
        }
        return actual.equals(IndexSchemaContract.requiredProjectionVersions());
    }

    private static Document repositoryClaim(IndexJob job, String workerId, long fence) {
        return new Document(REPO_ID, job.repositoryId().value()).append("activeJobId", job.id().value())
                .append("activeWorkerId", workerId).append("activeGenerationId", job.generationId().value()).append("fence", fence);
    }

    private static Document repositoryWithoutAuthorityForUncommittedJob(IndexJob job) {
        return new Document(REPO_ID, job.repositoryId().value()).append("activeJobId", new Document("$exists", false))
                .append("committedJobId", new Document("$ne", job.id().value()));
    }

    private static Document claimedJobFilter() {
        return new Document(ACTIVE, true).append("workerId", new Document("$exists", true))
                .append("fence", new Document("$exists", true)).append("claimUntil", new Document("$exists", true))
                .append("phase", new Document("$ne", IndexJobPhase.ACCEPTED.name()));
    }

    private void recoverRevokedClaims(Document queryFilter) {
        Query query = new BasicQuery(queryFilter);
        java.util.List<IndexJob> claimedJobs = template.find(query, Document.class, IndexCollections.INDEX_JOBS).stream()
                .map(MongoIndexJobStore::from).toList();
        for (IndexJob job : claimedJobs) {
            Document repository = template.getCollection(IndexCollections.REPOSITORIES)
                    .find(repositoryWithoutAuthorityForUncommittedJob(job)).first();
            if (Objects.nonNull(repository)) {
                failAfterRevocation(job, IndexFailureCategory.WORKER_INTERRUPTED);
            }
        }
    }

    private void releaseRepositoryClaimWithoutFence(IndexJob job, String workerId) {
        template.updateFirst(new BasicQuery(new Document(REPO_ID, job.repositoryId().value())
                        .append("activeJobId", job.id().value()).append("activeWorkerId", workerId)
                        .append("activeGenerationId", job.generationId().value())),
                new Update().unset("activeJobId").unset("activeWorkerId").unset("activeGenerationId").unset("claimUntil"),
                IndexCollections.REPOSITORIES);
    }

    private Optional<IndexJob> jobForClaim(Document claim) {
        Number fence = claim.get("fence", Number.class);
        if (Objects.isNull(fence)) {
            return Optional.empty();
        }
        Document job = template.getCollection(IndexCollections.INDEX_JOBS).find(new Document(JOB_ID, claim.getString("activeJobId"))
                .append(REPO_ID, claim.getString(REPO_ID)).append(ACTIVE, true)
                .append("workerId", claim.getString("activeWorkerId")).append("fence", fence.longValue())).first();
        return Optional.ofNullable(job).map(MongoIndexJobStore::from);
    }

    private static Document jobOwnership(IndexJob job, String workerId, long fence) {
        return new Document(JOB_ID, job.id().value()).append(REPO_ID, job.repositoryId().value()).append(ACTIVE, true)
                .append("workerId", workerId).append("fence", fence);
    }

    private static Document repositoryPointerFilter(RepositoryId repositoryId, PublishedGenerationPointer current,
                                                    PublishedGenerationPointer rollback) {
        Document filter = pointerDocument(current);
        filter.put(REPO_ID, repositoryId.value());
        for (java.util.Map.Entry<String, Object> entry : pointerDocument(rollback).entrySet()) {
            filter.put("rollbackPointer." + entry.getKey(), entry.getValue());
        }
        return filter;
    }

    private boolean sealed(RepositoryId repositoryId, PublishedGenerationPointer pointer) {
        Document manifest = template.getCollection(IndexCollections.GENERATION_MANIFESTS).find(new Document(REPO_ID, repositoryId.value())
                .append("sourceRevision", pointer.revision().value()).append("generationId", pointer.generationId().value())
                .append("identityDigest", pointer.manifestDigest().value()).append("writeState", "SEALED_VALID")).first();
        return Objects.nonNull(manifest);
    }

    private boolean matchesCommittedResult(Document repository, Document job) {
        Document intentDocument = job.get("publicationIntent", Document.class);
        if (Objects.isNull(intentDocument)) {
            return false;
        }
        IndexPublicationIntent intent = intentFrom(intentDocument);
        IndexJobOperation operation = intent.operation();
        if (operation == IndexJobOperation.ROLLBACK) {
            return pointerMatches(repository, intent.expectedRollback().orElseThrow(), job.getString(JOB_ID))
                    && rollbackPointerMatches(repository, intent.expectedCurrent().orElseThrow());
        }
        return pointerMatches(repository, new PublishedGenerationPointer(intent.targetRevision(), intent.targetGenerationId(),
                intent.targetManifestDigest(), job.getString(JOB_ID), repository.getDate("publishedAt").toInstant()), job.getString(JOB_ID))
                && sealedManifestOwnedBy(repository.getString(REPO_ID), intent, job)
                && expectedParentBecameRollback(repository, intent.expectedParent());
    }

    private static boolean pointerMatches(Document actual, PublishedGenerationPointer expected, String committedJobId) {
        return expected.revision().value().equals(actual.getString("revision"))
                && expected.generationId().value().equals(actual.getString("generationId"))
                && expected.manifestDigest().value().equals(actual.getString("manifestDigest"))
                && committedJobId.equals(actual.getString("committedJobId"));
    }

    private static boolean rollbackPointerMatches(Document repository, PublishedGenerationPointer expected) {
        Document rollback = repository.get("rollbackPointer", Document.class);
        return Objects.nonNull(rollback) && expected.revision().value().equals(rollback.getString("revision"))
                && expected.generationId().value().equals(rollback.getString("generationId"))
                && expected.manifestDigest().value().equals(rollback.getString("manifestDigest"))
                && expected.committedJobId().equals(rollback.getString("committedJobId"))
                && expected.publishedAt().equals(rollback.getDate("publishedAt").toInstant());
    }

    private boolean sealedManifestOwnedBy(String repositoryId, IndexPublicationIntent intent, Document job) {
        Number fence = job.get("fence", Number.class);
        if (Objects.isNull(fence)) {
            return false;
        }
        Document manifest = template.getCollection(IndexCollections.GENERATION_MANIFESTS).find(new Document(REPO_ID, repositoryId)
                .append("sourceRevision", intent.targetRevision().value()).append("generationId", intent.targetGenerationId().value())
                .append("identityDigest", intent.targetManifestDigest().value()).append("ownerJobId", intent.jobId().value())
                .append("ownerWorkerId", job.getString("workerId")).append("fence", fence.longValue())
                .append("writeState", "SEALED_VALID")).first();
        return Objects.nonNull(manifest);
    }

    private static boolean expectedParentBecameRollback(Document repository, Optional<PublishedGenerationPointer> expectedParent) {
        if (expectedParent.isPresent()) {
            return rollbackPointerMatches(repository, expectedParent.orElseThrow());
        }
        return !repository.containsKey("rollbackPointer");
    }

    private static Document pointerDocument(PublishedGenerationPointer pointer) {
        return new Document("revision", pointer.revision().value()).append("generationId", pointer.generationId().value())
                .append("manifestDigest", pointer.manifestDigest().value()).append("committedJobId", pointer.committedJobId())
                .append("publishedAt", Date.from(pointer.publishedAt()));
    }

    private static Document rollbackIntentDocument(IndexJobId jobId, RepositoryId repositoryId,
                                                   PublishedGenerationPointer expectedCurrent,
                                                   PublishedGenerationPointer expectedRollback) {
        return new Document("operation", IndexJobOperation.ROLLBACK.name()).append("jobId", jobId.value())
                .append(REPO_ID, repositoryId.value()).append("targetRevision", expectedRollback.revision().value())
                .append("targetGenerationId", expectedRollback.generationId().value())
                .append("targetManifestDigest", expectedRollback.manifestDigest().value())
                .append("expectedCurrent", pointerDocument(expectedCurrent))
                .append("expectedRollback", pointerDocument(expectedRollback));
    }

    private static Document intentDocument(IndexPublicationIntent intent) {
        Document document = new Document("operation", intent.operation().name()).append("jobId", intent.jobId().value())
                .append(REPO_ID, intent.repositoryId().value()).append("targetRevision", intent.targetRevision().value())
                .append("targetGenerationId", intent.targetGenerationId().value())
                .append("targetManifestDigest", intent.targetManifestDigest().value());
        intent.expectedParent().ifPresent(pointer -> document.append("expectedParent", pointerDocument(pointer)));
        intent.expectedCurrent().ifPresent(pointer -> document.append("expectedCurrent", pointerDocument(pointer)));
        intent.expectedRollback().ifPresent(pointer -> document.append("expectedRollback", pointerDocument(pointer)));
        return document;
    }

    private static IndexPublicationIntent intentFrom(Document document) {
        Optional<Document> parent = Optional.ofNullable(document.get("expectedParent", Document.class));
        Optional<Document> current = Optional.ofNullable(document.get("expectedCurrent", Document.class));
        Optional<Document> rollback = Optional.ofNullable(document.get("expectedRollback", Document.class));
        return new IndexPublicationIntent(new IndexJobId(document.getString("jobId")),
                IndexJobOperation.valueOf(document.getString("operation")), RepositoryId.of(document.getString(REPO_ID)),
                new RepositoryRevision(document.getString("targetRevision")), new GenerationId(document.getString("targetGenerationId")),
                new ManifestDigest(document.getString("targetManifestDigest")), parent.map(MongoIndexJobStore::pointerFrom),
                current.map(MongoIndexJobStore::pointerFrom), rollback.map(MongoIndexJobStore::pointerFrom));
    }

    private static Optional<PublishedGenerationPointer> pointerFromRepository(Document repository) {
        if (!repository.containsKey("revision")) {
            return Optional.empty();
        }
        return Optional.of(pointerFrom(repository));
    }

    private static Document serverExpiry(Duration claimLifetime) {
        return new Document("$dateAdd", new Document("startDate", "$$NOW").append("unit", "millisecond")
                .append("amount", claimLifetime.toMillis()));
    }

    private static void requirePositive(Duration duration, String name) {
        if (duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static Document claimJobDocument(MongoTemplate template, IndexJob job, String workerId, long fence, Date claimUntil) {
        return template.findAndModify(new BasicQuery(new Document(JOB_ID, job.id().value()).append(ACTIVE, true)
                        .append("phase", IndexJobPhase.ACCEPTED.name())),
                new Update().set("workerId", workerId).set("fence", fence).set("claimUntil", claimUntil)
                        .set("phase", IndexJobPhase.CHECKOUT.name()), FindAndModifyOptions.options().returnNew(true),
                Document.class, IndexCollections.INDEX_JOBS);
    }

    /** The parent pointer is frozen immediately after the repository claim and reused as publication's CAS predicate. */
    private boolean captureBuildParent(IndexJob job, String workerId, long fence, Document repository) {
        Update update = new Update().set("buildParentCaptured", true);
        pointerFromRepository(repository).ifPresent(pointer -> update.set("buildParent", pointerDocument(pointer)));
        UpdateResult captured = template.updateFirst(new BasicQuery(jobOwnership(job, workerId, fence)
                        .append("buildParentCaptured", new Document("$exists", false))), update, IndexCollections.INDEX_JOBS);
        return captured.getModifiedCount() == 1L;
    }

    private void releaseExactRepositoryClaim(IndexJob job, String workerId, long fence) {
        template.updateFirst(new BasicQuery(repositoryClaim(job, workerId, fence)), new Update().unset("activeJobId")
                .unset("activeWorkerId").unset("activeGenerationId").unset("claimUntil"), IndexCollections.REPOSITORIES);
    }

    private boolean mirrorManifestExpiry(IndexJob job, String workerId, long fence, Date claimUntil) {
        Document manifestFilter = new Document(REPO_ID, job.repositoryId().value()).append("generationId", job.generationId().value())
                .append("ownerJobId", job.id().value()).append("ownerWorkerId", workerId).append("fence", fence)
                .append("writeState", "WRITING");
        Document existing = template.getCollection(IndexCollections.GENERATION_MANIFESTS).find(manifestFilter).first();
        if (Objects.isNull(existing)) {
            return true;
        }
        UpdateResult updated = template.updateFirst(new BasicQuery(manifestFilter), new Update().set("sealUntil", claimUntil),
                IndexCollections.GENERATION_MANIFESTS);
        return updated.getModifiedCount() == 1;
    }

    private static PublishedGenerationPointer pointerFrom(Document document) {
        return new PublishedGenerationPointer(new RepositoryRevision(document.getString("revision")),
                new GenerationId(document.getString("generationId")), new ManifestDigest(document.getString("manifestDigest")),
                document.getString("committedJobId"), document.getDate("publishedAt").toInstant());
    }

    private static IndexJob from(Document document) {
        String workerId = document.getString("workerId");
        Number fence = document.get("fence", Number.class);
        Date until = document.getDate("claimUntil");
        String category = document.getString("failureCategory");
        return new IndexJob(new IndexJobId(document.getString(JOB_ID)), RepositoryId.of(document.getString(REPO_ID)),
                new RepositoryRevision(document.getString("revision")), new GenerationId(document.getString("generationId")),
                document.getLong("generation"), IndexJobPhase.valueOf(document.getString("phase")), Boolean.TRUE.equals(document.getBoolean(ACTIVE)),
                Optional.ofNullable(workerId), Optional.ofNullable(fence).map(value -> new RepositoryFence(value.longValue())),
                Optional.ofNullable(until).map(Date::toInstant), Optional.ofNullable(category).map(IndexFailureCategory::valueOf),
                Optional.ofNullable(document.getString("operation")).map(IndexJobOperation::valueOf).orElse(IndexJobOperation.BUILD));
    }

    @FunctionalInterface
    interface ClaimJobDocumentTransition {
        Document claim(MongoTemplate template, IndexJob job, String workerId, long fence, Date claimUntil);
    }
}
