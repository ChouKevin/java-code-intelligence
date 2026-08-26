package com.java.semantic.indexer.job;

import com.java.semantic.indexer.store.PublicationConflictException;
import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.index.IndexCollections;
import com.java.semantic.model.index.IndexSchemaContract;
import com.java.semantic.model.index.ManifestDigest;
import com.java.semantic.model.index.PublishedGenerationPointer;
import com.java.semantic.model.index.RollbackGenerationCommand;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
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
    public Optional<IndexJob> find(IndexJobId jobId) {
        return Optional.ofNullable(template.getCollection(IndexCollections.INDEX_JOBS).find(new Document(JOB_ID, jobId.value())).first())
                .map(MongoIndexJobStore::from);
    }

    @Override
    public Optional<IndexJob> start(IndexJobId jobId) {
        Document started = template.getCollection(IndexCollections.INDEX_JOBS).findOneAndUpdate(
                new Document(JOB_ID, jobId.value()).append(ACTIVE, true).append("phase", IndexJobPhase.ACCEPTED.name()),
                Updates.set("phase", IndexJobPhase.RUNNING.name()));
        if (Objects.isNull(started)) {
            return Optional.empty();
        }
        return find(jobId);
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
    }

    @Override
    public void failUnreconciledRunningJobs() {
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
        return Optional.ofNullable(completed).map(MongoIndexJobStore::from);
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

    private IndexJob insertBuild(RepositoryId repositoryId, RepositoryRevision revision, boolean rebuild,
                                 Optional<PublishedGenerationPointer> expectedParent) {
        IndexJobId jobId = IndexJobId.create();
        IndexJobTarget target = new IndexJobTarget(revision, new GenerationId("g-" + jobId.value().replace("-", "")), nextGeneration(repositoryId));
        Document job = targetDocument(jobId, repositoryId, target, IndexJobOperation.BUILD, rebuild);
        expectedParent.ifPresent(pointer -> job.append("expectedParent", pointerDocument(pointer)));
        insert(job, repositoryId);
        return from(job);
    }

    private IndexJob insertNoWork(RepositoryId repositoryId) {
        IndexJobId jobId = IndexJobId.create();
        Document job = new Document(JOB_ID, jobId.value()).append(REPOSITORY_ID, repositoryId.value()).append(ACTIVE, false)
                .append("phase", IndexJobPhase.COMPLETE.name()).append("operation", IndexJobOperation.NO_WORK.name()).append("createdAt", new Date());
        template.getCollection(IndexCollections.INDEX_JOBS).insertOne(job);
        return from(job);
    }

    private static Document targetDocument(IndexJobId jobId, RepositoryId repositoryId, IndexJobTarget target,
                                           IndexJobOperation operation, boolean rebuild) {
        return new Document(JOB_ID, jobId.value()).append(REPOSITORY_ID, repositoryId.value()).append("target", targetDocument(target)).append(ACTIVE, true)
                .append("phase", IndexJobPhase.ACCEPTED.name()).append("operation", operation.name()).append("rebuild", rebuild).append("createdAt", new Date());
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
        Document maximum = template.getCollection(IndexCollections.INDEX_JOBS).find(new Document(REPOSITORY_ID, repositoryId.value()))
                .sort(new Document("target.generation", -1)).limit(1).first();
        if (Objects.isNull(maximum)) {
            return 1L;
        }
        Document target = maximum.get("target", Document.class);
        if (Objects.isNull(target)) {
            return 1L;
        }
        Number generation = target.get("generation", Number.class);
        return Objects.isNull(generation) ? 1L : generation.longValue() + 1L;
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

    private static IndexJob from(Document document) {
        IndexJobOperation operation = IndexJobOperation.valueOf(document.getString("operation"));
        Optional<IndexJobTarget> target = Optional.empty();
        if (operation == IndexJobOperation.BUILD || operation == IndexJobOperation.ROLLBACK) {
            target = Optional.of(targetFrom(Objects.requireNonNull(document.get("target", Document.class), "target document is required")));
        }
        return new IndexJob(new IndexJobId(document.getString(JOB_ID)), RepositoryId.of(document.getString(REPOSITORY_ID)), target,
                IndexJobPhase.valueOf(document.getString("phase")), Boolean.TRUE.equals(document.getBoolean(ACTIVE)),
                Optional.ofNullable(document.getString("failureCategory")).map(IndexFailureCategory::valueOf), Boolean.TRUE.equals(document.getBoolean("rebuild")), operation);
    }

    private static IndexJobTarget targetFrom(Document document) {
        return new IndexJobTarget(new RepositoryRevision(document.getString("revision")), new GenerationId(document.getString("generationId")),
                Objects.requireNonNull(document.get("generation", Number.class), "target generation is required").longValue());
    }
}
