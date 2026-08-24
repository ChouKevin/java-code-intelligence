package com.java.semantic.indexer.store;

import com.java.semantic.model.index.GenerationWriteState;
import com.java.semantic.model.index.IndexCollections;
import com.java.semantic.model.index.IndexSchemaContract;
import com.java.semantic.model.index.PublishGenerationCommand;
import com.java.semantic.model.index.PublishedGenerationPointer;
import com.java.semantic.model.index.RollbackGenerationCommand;
import com.mongodb.MongoException;
import org.bson.Document;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.AggregationUpdate;
import org.springframework.data.mongodb.core.query.BasicQuery;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Atomically publishes or rolls back the one current repository pointer on a standalone MongoDB server. */
@Component
public final class MongoPublicationWriter implements PublicationPort {

    private static final String REPOSITORY_ID = "repoId";
    private static final String GENERATION_ID = "generationId";
    private static final String MANIFEST_DIGEST = "manifestDigest";
    private static final String REVISION = "revision";
    private static final String COMMITTED_JOB_ID = "committedJobId";
    private static final String PUBLISHED_AT = "publishedAt";
    private static final String ROLLBACK_POINTER = "rollbackPointer";

    private final MongoTemplate template;

    public MongoPublicationWriter(MongoTemplate template) {
        this.template = Objects.requireNonNull(template, "mongo template is required");
    }

    public PublishedGenerationPointer publish(PublishGenerationCommand command) {
        Objects.requireNonNull(command, "publish command is required");
        try {
            Date sealedUntil = requireSealedManifest(command);
            Document published = template.findAndModify(new BasicQuery(publicationFilter(command, sealedUntil)), publicationUpdate(command),
                    FindAndModifyOptions.options().returnNew(true), Document.class, IndexCollections.REPOSITORIES);
            if (Objects.isNull(published)) {
                throw new PublicationConflictException();
            }
            return pointerFrom(published);
        } catch (MongoException exception) {
            throw new SemanticIndexUnavailableException("SEMANTIC_INDEX_UNAVAILABLE", exception);
        } catch (org.springframework.dao.DataAccessResourceFailureException exception) {
            throw new SemanticIndexUnavailableException("SEMANTIC_INDEX_UNAVAILABLE", exception);
        }
    }

    /** Store-only rollback; it accepts only the exact bounded rollback pointer, never an arbitrary generation. */
    public PublishedGenerationPointer rollback(RollbackGenerationCommand command) {
        Objects.requireNonNull(command, "rollback command is required");
        try {
            requireSealedPointer(command.repositoryId().value(), command.expectedRollback());
            Document rolledBack = template.findAndModify(new BasicQuery(rollbackFilter(command)), rollbackUpdate(command),
                    FindAndModifyOptions.options().returnNew(true), Document.class, IndexCollections.REPOSITORIES);
            if (Objects.isNull(rolledBack)) {
                throw new PublicationConflictException();
            }
            return pointerFrom(rolledBack);
        } catch (MongoException exception) {
            throw new SemanticIndexUnavailableException("SEMANTIC_INDEX_UNAVAILABLE", exception);
        } catch (org.springframework.dao.DataAccessResourceFailureException exception) {
            throw new SemanticIndexUnavailableException("SEMANTIC_INDEX_UNAVAILABLE", exception);
        }
    }

    private Date requireSealedManifest(PublishGenerationCommand command) {
        Document filter = new Document(REPOSITORY_ID, command.repositoryId().value())
                .append("sourceRevision", command.targetRevision().value())
                .append(GENERATION_ID, command.targetGenerationId().value())
                .append("ownerJobId", command.activeJobId())
                .append("ownerWorkerId", command.activeWorkerId())
                .append("fence", command.activeFence().value())
                .append("identityDigest", command.sealedManifestDigest().value())
                .append("writeState", GenerationWriteState.SEALED_VALID.name());
        Document manifest = Optional.ofNullable(template.getCollection(IndexCollections.GENERATION_MANIFESTS).find(filter).first())
                .orElseThrow(PublicationConflictException::new);
        if (!compatibleAndValidated(manifest)) {
            throw new PublicationConflictException();
        }
        return manifest.getDate("sealUntil");
    }

    private void requireSealedPointer(String repositoryId, PublishedGenerationPointer pointer) {
        Document filter = new Document(REPOSITORY_ID, repositoryId)
                .append("sourceRevision", pointer.revision().value())
                .append(GENERATION_ID, pointer.generationId().value())
                .append("identityDigest", pointer.manifestDigest().value())
                .append("writeState", GenerationWriteState.SEALED_VALID.name());
        Document manifest = Optional.ofNullable(template.getCollection(IndexCollections.GENERATION_MANIFESTS).find(filter).first())
                .orElseThrow(PublicationConflictException::new);
        if (!compatibleAndValidated(manifest)) {
            throw new PublicationConflictException();
        }
    }

    private static boolean compatibleAndValidated(Document manifest) {
        Integer schemaVersion = manifest.getInteger("schemaVersion");
        if (!Integer.valueOf(IndexSchemaContract.SCHEMA_VERSION).equals(schemaVersion)) {
            return false;
        }
        List<Document> projectionVersions = manifest.getList("projectionVersions", Document.class);
        if (Objects.isNull(projectionVersions) || projectionVersions.isEmpty()) {
            return false;
        }
        java.util.LinkedHashMap<String, Integer> actual = new java.util.LinkedHashMap<>();
        for (Document version : projectionVersions) {
            String name = version.getString("name");
            Integer value = version.getInteger("version");
            if (Objects.isNull(name) || Objects.isNull(value)) {
                return false;
            }
            actual.put(name, value);
        }
        Document sealedCounts = manifest.get("sealedCollectionCounts", Document.class);
        Object writeEpoch = manifest.get("writeEpoch");
        Date sealUntil = manifest.getDate("sealUntil");
        String validationResult = manifest.getString("validationResult");
        Date validatedAt = manifest.getDate("validatedAt");
        return IndexSchemaContract.requiredProjectionVersions().equals(Map.copyOf(actual))
                && validSealedCounts(sealedCounts)
                && writeEpoch instanceof Number && ((Number) writeEpoch).longValue() >= 0L
                && Objects.nonNull(sealUntil)
                && "VALID".equals(validationResult)
                && Objects.nonNull(validatedAt);
    }

    private static boolean validSealedCounts(Document sealedCounts) {
        if (Objects.isNull(sealedCounts) || sealedCounts.isEmpty()) {
            return false;
        }
        for (Object count : sealedCounts.values()) {
            if (!(count instanceof Number) || ((Number) count).longValue() < 0L) {
                return false;
            }
        }
        return true;
    }

    private static Document publicationFilter(PublishGenerationCommand command, Date sealedUntil) {
        Document filter = activeLeaseFilter(command.repositoryId().value(), command.activeJobId(), command.activeWorkerId(),
                command.targetGenerationId().value(), command.activeFence().value()).append("claimUntil", sealedUntil);
        command.expectedParent().ifPresentOrElse(pointer -> addPointerMatch(filter, "", pointer),
                () -> requireNoCurrentPointer(filter));
        return filter;
    }

    private static Document rollbackFilter(RollbackGenerationCommand command) {
        Document filter = activeLeaseFilter(command.repositoryId().value(), command.activeJobId(), command.activeWorkerId(),
                command.expectedRollback().generationId().value(), command.activeFence().value());
        addPointerMatch(filter, "", command.expectedCurrent());
        addPointerMatch(filter, ROLLBACK_POINTER + ".", command.expectedRollback());
        return filter;
    }

    private static Document activeLeaseFilter(String repositoryId, String jobId, String workerId, String generationId, long fence) {
        return new Document(REPOSITORY_ID, repositoryId)
                .append("activeJobId", jobId)
                .append("activeWorkerId", workerId)
                .append("activeGenerationId", generationId)
                .append("fence", fence)
                .append("$expr", new Document("$gt", List.of("$claimUntil", "$$NOW")));
    }

    private static void requireNoCurrentPointer(Document filter) {
        filter.append(REVISION, new Document("$exists", false));
        filter.append(GENERATION_ID, new Document("$exists", false));
        filter.append(MANIFEST_DIGEST, new Document("$exists", false));
        filter.append(COMMITTED_JOB_ID, new Document("$exists", false));
        filter.append(PUBLISHED_AT, new Document("$exists", false));
    }

    private static void addPointerMatch(Document filter, String prefix, PublishedGenerationPointer pointer) {
        filter.append(prefix + REVISION, pointer.revision().value());
        filter.append(prefix + GENERATION_ID, pointer.generationId().value());
        filter.append(prefix + MANIFEST_DIGEST, pointer.manifestDigest().value());
        filter.append(prefix + COMMITTED_JOB_ID, pointer.committedJobId());
        filter.append(prefix + PUBLISHED_AT, Date.from(pointer.publishedAt()));
    }

    private static AggregationUpdate publicationUpdate(PublishGenerationCommand command) {
        AggregationUpdate update = AggregationUpdate.update();
        if (command.expectedParent().isPresent()) {
            update = copyCurrentToRollback(update);
        } else {
            update = update.unset(ROLLBACK_POINTER);
        }
        return update
                .set(REVISION).toValue(command.targetRevision().value())
                .set(GENERATION_ID).toValue(command.targetGenerationId().value())
                .set(MANIFEST_DIGEST).toValue(command.sealedManifestDigest().value())
                .set(COMMITTED_JOB_ID).toValue(command.activeJobId())
                .set(PUBLISHED_AT).toValueOf("$$NOW")
                .unset("activeJobId", "activeWorkerId", "activeGenerationId", "claimUntil");
    }

    private static AggregationUpdate rollbackUpdate(RollbackGenerationCommand command) {
        return AggregationUpdate.update()
                .set(REVISION).toValue(command.expectedRollback().revision().value())
                .set(GENERATION_ID).toValue(command.expectedRollback().generationId().value())
                .set(MANIFEST_DIGEST).toValue(command.expectedRollback().manifestDigest().value())
                .set(COMMITTED_JOB_ID).toValue(command.activeJobId())
                .set(PUBLISHED_AT).toValueOf("$$NOW")
                .set(ROLLBACK_POINTER + "." + REVISION).toValue(command.expectedCurrent().revision().value())
                .set(ROLLBACK_POINTER + "." + GENERATION_ID).toValue(command.expectedCurrent().generationId().value())
                .set(ROLLBACK_POINTER + "." + MANIFEST_DIGEST).toValue(command.expectedCurrent().manifestDigest().value())
                .set(ROLLBACK_POINTER + "." + COMMITTED_JOB_ID).toValue(command.expectedCurrent().committedJobId())
                .set(ROLLBACK_POINTER + "." + PUBLISHED_AT).toValue(Date.from(command.expectedCurrent().publishedAt()))
                .unset("activeJobId", "activeWorkerId", "activeGenerationId", "claimUntil");
    }

    private static AggregationUpdate copyCurrentToRollback(AggregationUpdate update) {
        return update.set(ROLLBACK_POINTER + "." + REVISION).toValueOf(REVISION)
                .set(ROLLBACK_POINTER + "." + GENERATION_ID).toValueOf(GENERATION_ID)
                .set(ROLLBACK_POINTER + "." + MANIFEST_DIGEST).toValueOf(MANIFEST_DIGEST)
                .set(ROLLBACK_POINTER + "." + COMMITTED_JOB_ID).toValueOf(COMMITTED_JOB_ID)
                .set(ROLLBACK_POINTER + "." + PUBLISHED_AT).toValueOf(PUBLISHED_AT);
    }

    private static PublishedGenerationPointer pointerFrom(Document repository) {
        return new PublishedGenerationPointer(
                new com.java.semantic.model.repository.RepositoryRevision(repository.getString(REVISION)),
                new com.java.semantic.model.index.GenerationId(repository.getString(GENERATION_ID)),
                new com.java.semantic.model.index.ManifestDigest(repository.getString(MANIFEST_DIGEST)),
                repository.getString(COMMITTED_JOB_ID),
                Instant.ofEpochMilli(repository.getDate(PUBLISHED_AT).getTime()));
    }
}
