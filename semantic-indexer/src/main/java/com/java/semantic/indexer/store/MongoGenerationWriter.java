package com.java.semantic.indexer.store;

import com.java.semantic.model.index.GenerationWriteState;
import com.java.semantic.model.index.IndexSchemaContract;
import com.java.semantic.model.index.IndexSchemaContract.ImmutablePayloadCollectionSpec;
import com.java.semantic.model.index.IndexSchemaContract.PayloadScope;
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

    /** Fails before checkout or exporter invocation when an administrator has not installed the current contract. */
    public void verifySchemaBeforeGeneration() {
        new MongoIndexSchemaReadinessVerifier(template).verify();
    }

    public void insertManifest(GenerationWriteContext context, Document manifest) {
        Objects.requireNonNull(context, "generation write context is required");
        verifyRunningBuild(context);
        try {
            Document owned = new Document(manifest).append("repoId", context.repositoryId().value())
                    .append("generationId", context.generationId().value()).append("ownerJobId", context.jobId());
            insertImmutable("generation_manifests", owned, List.of("repoId", "generationId"));
        } catch (MongoException exception) {
            throw new SemanticIndexUnavailableException("SEMANTIC_INDEX_UNAVAILABLE", exception);
        } catch (DataAccessResourceFailureException exception) {
            throw new SemanticIndexUnavailableException("SEMANTIC_INDEX_UNAVAILABLE", exception);
        }
    }

    public void writeBatch(GenerationWriteContext context, String batchId, List<StoredDocument> documents) {
        Objects.requireNonNull(context, "generation write context is required");
        Objects.requireNonNull(batchId, "batch id is required");
        List<StoredDocument> immutableDocuments = List.copyOf(documents);
        verifyRunningBuild(context);
        batchRegistrationGate.beforeManifestRegistration();
        try {
            registerOutstandingOnManifest(context, batchId);
        } catch (SemanticIndexUnavailableException exception) {
            failGeneration(context, batchId);
            throw exception;
        }
        batchRegistrationGate.afterManifestRegistration();
        try {
            markOutstanding(context, batchId);
            for (StoredDocument stored : immutableDocuments) {
                ImmutablePayloadCollectionSpec collection = IndexSchemaContract.immutablePayloadCollection(stored.collection());
                insertImmutable(collection.name(), scopedDocument(collection, stored.document(), context), collection.identityFields());
            }
            acknowledgeBatch(context, batchId);
        } catch (MongoException exception) {
            failGeneration(context, batchId);
            throw new SemanticIndexUnavailableException("SEMANTIC_INDEX_UNAVAILABLE", exception);
        } catch (DataAccessResourceFailureException exception) {
            failGeneration(context, batchId);
            throw new SemanticIndexUnavailableException("SEMANTIC_INDEX_UNAVAILABLE", exception);
        } catch (RuntimeException exception) {
            failGeneration(context, batchId);
            throw exception;
        }
    }

    public void seal(GenerationWriteContext context, String digest) {
        try {
            verifyRunningBuild(context);
            Document query = ownedWritingManifest(context).append("identityDigest", digest)
                    .append("validationResult", "VALID").append("validatedAt", new Document("$exists", true))
                    .append("sealedCollectionCounts", new Document("$exists", true))
                    .append("$expr", new Document("$and", List.of(noBatches("outstandingBatches"),
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

    private void verifyRunningBuild(GenerationWriteContext context) {
        Document query = new Document("jobId", context.jobId()).append("repoId", context.repositoryId().value())
                .append("target.generationId", context.generationId().value()).append("operation", "BUILD")
                .append("phase", "RUNNING").append("active", true);
        try {
            if (Optional.ofNullable(template.getCollection("index_jobs").find(query).first()).isEmpty()) {
                throw new IllegalStateException("index job is not an active running build");
            }
        } catch (DataAccessResourceFailureException exception) {
            throw new SemanticIndexUnavailableException("SEMANTIC_INDEX_UNAVAILABLE", exception);
        } catch (MongoException exception) {
            throw new SemanticIndexUnavailableException("SEMANTIC_INDEX_UNAVAILABLE", exception);
        }
    }

    private void markOutstanding(GenerationWriteContext context, String batchId) {
        try {
            long matched = template.getCollection("index_jobs").updateOne(Filters.and(Filters.eq("jobId", context.jobId()),
                            Filters.eq("repoId", context.repositoryId().value()), Filters.eq("target.generationId", context.generationId().value()),
                            Filters.eq("operation", "BUILD"), Filters.eq("phase", "RUNNING"), Filters.eq("active", true),
                            Filters.ne("outstandingBatches", batchId), Filters.ne("acknowledgedBatches", batchId)),
                    Updates.addToSet("outstandingBatches", batchId)).getModifiedCount();
            if (matched != 1L) { throw new IllegalStateException("index job batch registration failed closed"); }
        } catch (MongoException exception) {
            throw new SemanticIndexUnavailableException("SEMANTIC_INDEX_UNAVAILABLE", exception);
        } catch (DataAccessResourceFailureException exception) {
            throw new SemanticIndexUnavailableException("SEMANTIC_INDEX_UNAVAILABLE", exception);
        }
    }

    private void registerOutstandingOnManifest(GenerationWriteContext context, String batchId) {
        Document query = ownedWritingManifest(context).append("outstandingBatches", new Document("$ne", batchId))
                .append("acknowledgedBatches", new Document("$ne", batchId))
                .append("validationResult", new Document("$exists", false));
        try {
            long changed = template.getCollection("generation_manifests").updateOne(query,
                    Updates.addToSet("outstandingBatches", batchId)).getModifiedCount();
            if (changed != 1L) {
                abandonGenerationAfterAmbiguousBatchRegistration(context, batchId);
                throw new IllegalStateException("generation batch registration failed closed");
            }
        } catch (MongoException exception) {
            throw new SemanticIndexUnavailableException("SEMANTIC_INDEX_UNAVAILABLE", exception);
        } catch (DataAccessResourceFailureException exception) {
            throw new SemanticIndexUnavailableException("SEMANTIC_INDEX_UNAVAILABLE", exception);
        }
    }

    private void abandonGenerationAfterAmbiguousBatchRegistration(GenerationWriteContext context, String batchId) {
        Document ambiguousRegistration = ownedWritingManifest(context).append("$or", List.of(
                new Document("outstandingBatches", batchId), new Document("acknowledgedBatches", batchId)));
        Optional<Document> manifest = Optional.ofNullable(template.getCollection("generation_manifests")
                .find(ambiguousRegistration).first());
        if (manifest.isPresent()) {
            failGeneration(context, batchId);
        }
    }

    private void acknowledgeBatch(GenerationWriteContext context, String batchId) {
        long jobChanged = template.getCollection("index_jobs").updateOne(Filters.and(Filters.eq("jobId", context.jobId()),
                        Filters.eq("repoId", context.repositoryId().value()), Filters.eq("target.generationId", context.generationId().value()),
                        Filters.eq("operation", "BUILD"), Filters.eq("phase", "RUNNING"), Filters.eq("active", true),
                        Filters.eq("outstandingBatches", batchId)),
                Updates.combine(Updates.pull("outstandingBatches", batchId), Updates.addToSet("acknowledgedBatches", batchId))).getModifiedCount();
        if (jobChanged != 1L) { throw new IllegalStateException("index job batch acknowledgement failed closed"); }
        long manifestChanged = template.getCollection("generation_manifests").updateOne(ownedWritingManifest(context)
                        .append("outstandingBatches", batchId),
                Updates.combine(Updates.pull("outstandingBatches", batchId), Updates.addToSet("acknowledgedBatches", batchId))).getModifiedCount();
        if (manifestChanged != 1L) { throw new IllegalStateException("generation batch acknowledgement failed closed"); }
    }

    private void failGeneration(GenerationWriteContext context, String batchId) {
        try {
            long jobChanged = template.getCollection("index_jobs").updateOne(Filters.and(Filters.eq("jobId", context.jobId()),
                            Filters.eq("repoId", context.repositoryId().value()), Filters.eq("target.generationId", context.generationId().value()),
                            Filters.eq("operation", "BUILD"), Filters.eq("phase", "RUNNING"), Filters.eq("active", true)),
                    Updates.addToSet("failedOrAmbiguousBatches", batchId)).getModifiedCount();
            long manifestChanged = template.getCollection("generation_manifests").updateOne(ownedWritingManifest(context),
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
        Document expectedWithoutId = new Document(expected);
        expectedWithoutId.remove("_id");
        return actualWithoutId.equals(expectedWithoutId);
    }

    private static Document scopedDocument(ImmutablePayloadCollectionSpec collection, Document source, GenerationWriteContext context) {
        Document document = new Document(source);
        if (collection.scope() == PayloadScope.GENERATION) {
            document.put("repoId", context.repositoryId().value());
            document.put("generationId", context.generationId().value());
        } else {
            document.remove("repoId");
            document.remove("generationId");
        }
        return document;
    }

    private static Document ownedWritingManifest(GenerationWriteContext context) {
        return new Document("repoId", context.repositoryId().value()).append("generationId", context.generationId().value())
                .append("ownerJobId", context.jobId())
                .append("writeState", GenerationWriteState.WRITING.name());
    }

    private static Document noBatches(String field) {
        return new Document("$eq", List.of(new Document("$size", new Document("$ifNull", List.of("$" + field, List.of()))), 0));
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
