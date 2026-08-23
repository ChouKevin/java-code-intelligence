package com.java.semantic.indexer.store;

import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.index.GenerationWriteState;
import com.java.semantic.model.index.IndexSchemaContract;
import com.java.semantic.model.index.IndexSchemaContract.ImmutablePayloadCollectionSpec;
import com.java.semantic.model.index.IndexSchemaContract.PayloadScope;
import com.java.semantic.model.repository.RepositoryId;
import com.mongodb.MongoException;
import com.mongodb.MongoWriteException;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Insert-only writer for an unpublished generation. It never creates schema. */
public final class MongoGenerationWriter {
    private final MongoTemplate template;
    private final BatchRegistrationGate batchRegistrationGate;

    public MongoGenerationWriter(MongoTemplate template) { this(template, () -> { }); }

    MongoGenerationWriter(MongoTemplate template, BatchRegistrationGate batchRegistrationGate) {
        this.template = Objects.requireNonNull(template, "mongo template is required");
        this.batchRegistrationGate = Objects.requireNonNull(batchRegistrationGate, "batch registration gate is required");
    }

    public void insertManifest(Document manifest) {
        try {
            insertImmutable("generation_manifests", manifest, List.of("repoId", "generationId"));
        } catch (MongoException exception) {
            throw new SemanticIndexUnavailableException("SEMANTIC_INDEX_UNAVAILABLE", exception);
        } catch (DataAccessResourceFailureException exception) {
            throw new SemanticIndexUnavailableException("SEMANTIC_INDEX_UNAVAILABLE", exception);
        }
    }

    public void writeBatch(GenerationLease lease, String batchId, List<StoredDocument> documents) {
        Objects.requireNonNull(lease, "generation lease is required");
        Objects.requireNonNull(batchId, "batch id is required");
        List<StoredDocument> immutableDocuments = List.copyOf(documents);
        verifyLease(lease);
        batchRegistrationGate.beforeManifestRegistration();
        try {
            registerOutstandingOnManifest(lease, batchId);
        } catch (SemanticIndexUnavailableException exception) {
            failGeneration(lease, batchId);
            throw exception;
        }
        batchRegistrationGate.afterManifestRegistration();
        try {
            markOutstanding(lease, batchId);
            for (StoredDocument stored : immutableDocuments) {
                ImmutablePayloadCollectionSpec collection = IndexSchemaContract.immutablePayloadCollection(stored.collection());
                insertImmutable(collection.name(), scopedDocument(collection, stored.document(), lease), collection.identityFields());
            }
            acknowledgeBatch(lease, batchId);
        } catch (MongoException exception) {
            failGeneration(lease, batchId);
            throw new SemanticIndexUnavailableException("SEMANTIC_INDEX_UNAVAILABLE", exception);
        } catch (DataAccessResourceFailureException exception) {
            failGeneration(lease, batchId);
            throw new SemanticIndexUnavailableException("SEMANTIC_INDEX_UNAVAILABLE", exception);
        } catch (RuntimeException exception) {
            failGeneration(lease, batchId);
            throw exception;
        }
    }

    public void seal(GenerationLease lease, String digest) {
        try {
            verifyLease(lease);
            Document query = ownedWritingManifest(lease).append("identityDigest", digest)
                    .append("$expr", new Document("$and", List.of(
                            new Document("$gt", List.of("$sealUntil", "$$NOW")),
                            noBatches("outstandingBatches"),
                            noBatches("failedOrAmbiguousBatches"))));
            long changed = template.getCollection("generation_manifests").updateOne(query,
                    Updates.set("writeState", GenerationWriteState.SEALED_VALID.name())).getModifiedCount();
            if (changed != 1L) { throw new IllegalStateException("generation seal precondition failed"); }
        } catch (MongoException exception) {
            throw new SemanticIndexUnavailableException("SEMANTIC_INDEX_UNAVAILABLE", exception);
        } catch (DataAccessResourceFailureException exception) {
            throw new SemanticIndexUnavailableException("SEMANTIC_INDEX_UNAVAILABLE", exception);
        }
    }

    /** Mirrors the exact coordinator expiry into the owned WRITING manifest, or fails closed. */
    public void mirrorSealUntil(GenerationLease lease, java.util.Date successfulClaimUntil) {
        Objects.requireNonNull(successfulClaimUntil, "claim expiry is required");
        Document repository = new Document("repoId", lease.repositoryId().value()).append("activeJobId", lease.jobId())
                .append("activeWorkerId", lease.workerId()).append("activeGenerationId", lease.generationId().value())
                .append("fence", lease.fence()).append("claimUntil", successfulClaimUntil)
                .append("$expr", new Document("$gt", List.of("$claimUntil", "$$NOW")));
        try {
            if (Optional.ofNullable(template.getCollection("repositories").find(repository).first()).isEmpty()) {
                throw new IllegalStateException("successful lease expiry no longer belongs to generation");
            }
            Document manifest = new Document("repoId", lease.repositoryId().value()).append("generationId", lease.generationId().value())
                    .append("ownerJobId", lease.jobId()).append("ownerWorkerId", lease.workerId()).append("fence", lease.fence())
                    .append("writeState", GenerationWriteState.WRITING.name());
            long modified = template.getCollection("generation_manifests").updateOne(manifest, Updates.set("sealUntil", successfulClaimUntil)).getModifiedCount();
            if (modified != 1L) { throw new IllegalStateException("manifest lease mirror failed closed"); }
        } catch (MongoException exception) {
            throw new SemanticIndexUnavailableException("SEMANTIC_INDEX_UNAVAILABLE", exception);
        } catch (DataAccessResourceFailureException exception) {
            throw new SemanticIndexUnavailableException("SEMANTIC_INDEX_UNAVAILABLE", exception);
        }
    }

    private void verifyLease(GenerationLease lease) {
        Document query = new Document("repoId", lease.repositoryId().value()).append("activeJobId", lease.jobId())
                .append("activeWorkerId", lease.workerId()).append("activeGenerationId", lease.generationId().value())
                .append("fence", lease.fence()).append("$expr", new Document("$gt", List.of("$claimUntil", "$$NOW")));
        try {
            if (Optional.ofNullable(template.getCollection("repositories").find(query).first()).isEmpty()) {
                throw new IllegalStateException("generation lease is not active");
            }
            if (Optional.ofNullable(template.getCollection("index_jobs").find(Filters.and(Filters.eq("jobId", lease.jobId()),
                    Filters.eq("repoId", lease.repositoryId().value()), Filters.eq("state", "ACTIVE"))).first()).isEmpty()) {
                throw new IllegalStateException("index job is not active");
            }
        } catch (DataAccessResourceFailureException exception) {
            throw new SemanticIndexUnavailableException("SEMANTIC_INDEX_UNAVAILABLE", exception);
        } catch (MongoException exception) {
            throw new SemanticIndexUnavailableException("SEMANTIC_INDEX_UNAVAILABLE", exception);
        }
    }

    private void markOutstanding(GenerationLease lease, String batchId) {
        try {
            long matched = template.getCollection("index_jobs").updateOne(Filters.and(Filters.eq("jobId", lease.jobId()),
                            Filters.eq("repoId", lease.repositoryId().value()), Filters.eq("state", "ACTIVE"),
                            Filters.ne("outstandingBatches", batchId), Filters.ne("acknowledgedBatches", batchId)),
                    Updates.addToSet("outstandingBatches", batchId)).getModifiedCount();
            if (matched != 1L) { throw new IllegalStateException("index job batch registration failed closed"); }
        } catch (MongoException exception) {
            throw new SemanticIndexUnavailableException("SEMANTIC_INDEX_UNAVAILABLE", exception);
        } catch (DataAccessResourceFailureException exception) {
            throw new SemanticIndexUnavailableException("SEMANTIC_INDEX_UNAVAILABLE", exception);
        }
    }

    private void registerOutstandingOnManifest(GenerationLease lease, String batchId) {
        Document query = ownedWritingManifest(lease).append("outstandingBatches", new Document("$ne", batchId))
                .append("acknowledgedBatches", new Document("$ne", batchId))
                .append("$expr", new Document("$gt", List.of("$sealUntil", "$$NOW")));
        try {
            long changed = template.getCollection("generation_manifests").updateOne(query,
                    Updates.addToSet("outstandingBatches", batchId)).getModifiedCount();
            if (changed != 1L) {
                abandonGenerationAfterAmbiguousBatchRegistration(lease, batchId);
                throw new IllegalStateException("generation batch registration failed closed");
            }
        } catch (MongoException exception) {
            throw new SemanticIndexUnavailableException("SEMANTIC_INDEX_UNAVAILABLE", exception);
        } catch (DataAccessResourceFailureException exception) {
            throw new SemanticIndexUnavailableException("SEMANTIC_INDEX_UNAVAILABLE", exception);
        }
    }

    private void abandonGenerationAfterAmbiguousBatchRegistration(GenerationLease lease, String batchId) {
        Document ambiguousRegistration = ownedWritingManifest(lease).append("$or", List.of(
                new Document("outstandingBatches", batchId), new Document("acknowledgedBatches", batchId)));
        Optional<Document> manifest = Optional.ofNullable(template.getCollection("generation_manifests")
                .find(ambiguousRegistration).first());
        if (manifest.isPresent()) {
            failGeneration(lease, batchId);
        }
    }

    private void acknowledgeBatch(GenerationLease lease, String batchId) {
        long jobChanged = template.getCollection("index_jobs").updateOne(Filters.and(Filters.eq("jobId", lease.jobId()),
                        Filters.eq("repoId", lease.repositoryId().value()), Filters.eq("state", "ACTIVE"),
                        Filters.eq("outstandingBatches", batchId)),
                Updates.combine(Updates.pull("outstandingBatches", batchId), Updates.addToSet("acknowledgedBatches", batchId))).getModifiedCount();
        if (jobChanged != 1L) { throw new IllegalStateException("index job batch acknowledgement failed closed"); }
        long manifestChanged = template.getCollection("generation_manifests").updateOne(ownedWritingManifest(lease)
                        .append("outstandingBatches", batchId),
                Updates.combine(Updates.pull("outstandingBatches", batchId), Updates.addToSet("acknowledgedBatches", batchId))).getModifiedCount();
        if (manifestChanged != 1L) { throw new IllegalStateException("generation batch acknowledgement failed closed"); }
    }

    private void failGeneration(GenerationLease lease, String batchId) {
        try {
            long jobChanged = template.getCollection("index_jobs").updateOne(Filters.and(Filters.eq("jobId", lease.jobId()),
                            Filters.eq("repoId", lease.repositoryId().value()), Filters.eq("state", "ACTIVE")),
                    Updates.combine(Updates.addToSet("failedOrAmbiguousBatches", batchId), Updates.set("state", "FAILED"))).getModifiedCount();
            long manifestChanged = template.getCollection("generation_manifests").updateOne(ownedWritingManifest(lease),
                    Updates.combine(Updates.addToSet("failedOrAmbiguousBatches", batchId), Updates.set("writeState", GenerationWriteState.FAILED.name()))).getModifiedCount();
            if (jobChanged != 1L || manifestChanged != 1L) { throw new IllegalStateException("generation failure recording failed closed"); }
        } catch (MongoException exception) {
            throw new SemanticIndexUnavailableException("SEMANTIC_INDEX_UNAVAILABLE", exception);
        } catch (DataAccessResourceFailureException exception) {
            throw new SemanticIndexUnavailableException("SEMANTIC_INDEX_UNAVAILABLE", exception);
        }
    }

    private void insertImmutable(String collection, Document document, List<String> identityFields) {
        try {
            template.getCollection(collection).insertOne(new Document(document));
        } catch (MongoWriteException exception) {
            Document identity = new Document();
            for (String field : identityFields) { identity.put(field, document.get(field)); }
            Optional<Document> existing = Optional.ofNullable(template.getCollection(collection).find(identity).first());
            if (existing.isEmpty() || !sameDocument(existing.orElseThrow(), document)) {
                throw new IllegalStateException("immutable document conflict in " + collection, exception);
            }
        }
    }

    private static boolean sameDocument(Document existing, Document expected) {
        Document actualWithoutId = new Document(existing);
        actualWithoutId.remove("_id");
        return actualWithoutId.equals(expected);
    }

    private static Document scopedDocument(ImmutablePayloadCollectionSpec collection, Document source, GenerationLease lease) {
        Document document = new Document(source);
        if (collection.scope() == PayloadScope.GENERATION) {
            document.put("repoId", lease.repositoryId().value());
            document.put("generationId", lease.generationId().value());
        } else {
            document.remove("repoId");
            document.remove("generationId");
        }
        return document;
    }

    private static Document ownedWritingManifest(GenerationLease lease) {
        return new Document("repoId", lease.repositoryId().value()).append("generationId", lease.generationId().value())
                .append("ownerJobId", lease.jobId()).append("ownerWorkerId", lease.workerId()).append("fence", lease.fence())
                .append("writeState", GenerationWriteState.WRITING.name());
    }

    private static Document noBatches(String field) {
        return new Document("$eq", List.of(new Document("$size", new Document("$ifNull", List.of("$" + field, List.of()))), 0));
    }

    public record GenerationLease(RepositoryId repositoryId, GenerationId generationId, String jobId, String workerId, long fence) {
        public GenerationLease { repositoryId = Objects.requireNonNull(repositoryId, "repository id is required"); generationId = Objects.requireNonNull(generationId, "generation id is required"); jobId = Objects.requireNonNull(jobId, "job id is required"); workerId = Objects.requireNonNull(workerId, "worker id is required"); }
    }
    public record StoredDocument(String collection, Document document) {
        public StoredDocument { collection = Objects.requireNonNull(collection, "collection is required"); document = new Document(Objects.requireNonNull(document, "document is required")); }
    }

    @FunctionalInterface
    interface BatchRegistrationGate {
        void beforeManifestRegistration();
        default void afterManifestRegistration() { }
    }
}
