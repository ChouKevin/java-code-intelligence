package com.java.semantic.indexer.store;

import com.java.semantic.model.index.GenerationWriteState;
import com.java.semantic.model.index.IndexCollections;
import com.java.semantic.model.index.IndexSchemaContract;
import com.java.semantic.model.index.PublishGenerationCommand;
import com.java.semantic.model.index.PublishedGenerationPointer;
import com.java.semantic.model.index.RollbackGenerationCommand;
import com.mongodb.MongoException;
import com.mongodb.client.model.UpdateOptions;
import org.bson.Document;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Pointer publication is compare-and-set only; repository documents never carry job ownership. */
@Component
public final class MongoPublicationWriter implements PublicationPort {
    private static final String REPOSITORY_ID = "repoId";
    private static final String REVISION = "revision";
    private static final String GENERATION_ID = "generationId";
    private static final String MANIFEST_DIGEST = "manifestDigest";
    private static final String COMMITTED_JOB_ID = "committedJobId";
    private static final String PUBLISHED_AT = "publishedAt";
    private static final String CURRENT_POINTER = "currentPointer";
    private static final String ROLLBACK_POINTER = "rollbackPointer";

    private final MongoTemplate template;

    public MongoPublicationWriter(MongoTemplate template) {
        this.template = Objects.requireNonNull(template, "mongo template is required");
    }

    @Override
    public PublishedGenerationPointer publish(PublishGenerationCommand command) {
        Objects.requireNonNull(command, "publish command is required");
        try {
            requireRunningBuildJob(command);
            requireSealedBuildManifest(command);
            Date publishedAt = new Date();
            Document filter = publicationFilter(command);
            Document update = publicationUpdate(command, publishedAt);
            long changed = template.getCollection(IndexCollections.REPOSITORIES).updateOne(filter, update,
                    new UpdateOptions().upsert(command.expectedParent().isEmpty())).getModifiedCount();
            if (command.expectedParent().isPresent() && changed != 1L) {
                throw new PublicationConflictException();
            }
            return new PublishedGenerationPointer(command.targetRevision(), command.targetGenerationId(), command.sealedManifestDigest(),
                    command.jobId(), publishedAt.toInstant());
        } catch (MongoException exception) {
            throw new SemanticIndexUnavailableException("SEMANTIC_INDEX_UNAVAILABLE", exception);
        } catch (DataAccessResourceFailureException exception) {
            throw new SemanticIndexUnavailableException("SEMANTIC_INDEX_UNAVAILABLE", exception);
        }
    }

    @Override
    public PublishedGenerationPointer rollback(RollbackGenerationCommand command) {
        Objects.requireNonNull(command, "rollback command is required");
        try {
            requireRunningRollbackJob(command);
            requireSealedPointer(command.repositoryId().value(), command.expectedRollback());
            Date publishedAt = new Date();
            Document filter = pointerMatch(new Document(REPOSITORY_ID, command.repositoryId().value()), CURRENT_POINTER + ".", command.expectedCurrent());
            pointerMatch(filter, ROLLBACK_POINTER + ".", command.expectedRollback());
            long changed = template.getCollection(IndexCollections.REPOSITORIES).updateOne(filter, rollbackUpdate(command, publishedAt)).getModifiedCount();
            if (changed != 1L) {
                throw new PublicationConflictException();
            }
            return new PublishedGenerationPointer(command.expectedRollback().revision(), command.expectedRollback().generationId(),
                    command.expectedRollback().manifestDigest(), command.jobId(), publishedAt.toInstant());
        } catch (MongoException exception) {
            throw new SemanticIndexUnavailableException("SEMANTIC_INDEX_UNAVAILABLE", exception);
        } catch (DataAccessResourceFailureException exception) {
            throw new SemanticIndexUnavailableException("SEMANTIC_INDEX_UNAVAILABLE", exception);
        }
    }

    private void requireRunningBuildJob(PublishGenerationCommand command) {
        Document filter = runningJobFilter(command.jobId(), command.repositoryId().value(), "BUILD", command.targetRevision().value(),
                command.targetGenerationId().value());
        if (Objects.isNull(template.getCollection(IndexCollections.INDEX_JOBS).find(filter).first())) {
            throw new PublicationConflictException();
        }
    }

    private void requireRunningRollbackJob(RollbackGenerationCommand command) {
        Document filter = runningJobFilter(command.jobId(), command.repositoryId().value(), "ROLLBACK", command.expectedRollback().revision().value(),
                command.expectedRollback().generationId().value());
        pointerMatch(filter, "expectedCurrent.", command.expectedCurrent());
        pointerMatch(filter, "expectedRollback.", command.expectedRollback());
        if (Objects.isNull(template.getCollection(IndexCollections.INDEX_JOBS).find(filter).first())) {
            throw new PublicationConflictException();
        }
    }

    private static Document runningJobFilter(String jobId, String repositoryId, String operation, String revision, String generationId) {
        return new Document("jobId", jobId).append(REPOSITORY_ID, repositoryId).append("active", true).append("phase", "RUNNING")
                .append("operation", operation).append("target.revision", revision).append("target.generationId", generationId);
    }

    private void requireSealedBuildManifest(PublishGenerationCommand command) {
        Document filter = sealedManifestFilter(command.repositoryId().value(), command.targetRevision().value(), command.targetGenerationId().value(),
                command.sealedManifestDigest().value()).append("ownerJobId", command.jobId());
        Document manifest = template.getCollection(IndexCollections.GENERATION_MANIFESTS).find(filter).first();
        if (!compatibleAndValidated(manifest)) {
            throw new PublicationConflictException();
        }
    }

    private void requireSealedPointer(String repositoryId, PublishedGenerationPointer pointer) {
        Document manifest = template.getCollection(IndexCollections.GENERATION_MANIFESTS)
                .find(sealedManifestFilter(repositoryId, pointer.revision().value(), pointer.generationId().value(), pointer.manifestDigest().value())).first();
        if (!compatibleAndValidated(manifest)) {
            throw new PublicationConflictException();
        }
    }

    private static Document sealedManifestFilter(String repositoryId, String revision, String generationId, String digest) {
        return new Document(REPOSITORY_ID, repositoryId).append("sourceRevision", revision).append(GENERATION_ID, generationId)
                .append("identityDigest", digest).append("writeState", GenerationWriteState.SEALED_VALID.name());
    }

    private static boolean compatibleAndValidated(Document manifest) {
        if (Objects.isNull(manifest) || !Integer.valueOf(IndexSchemaContract.SCHEMA_VERSION).equals(manifest.getInteger("schemaVersion"))) {
            return false;
        }
        List<Document> projectionVersions = manifest.getList("projectionVersions", Document.class);
        if (Objects.isNull(projectionVersions) || projectionVersions.isEmpty()) {
            return false;
        }
        Map<String, Integer> actual = new LinkedHashMap<>();
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
        return IndexSchemaContract.requiredProjectionVersions().equals(Map.copyOf(actual)) && validSealedCounts(sealedCounts)
                && writeEpoch instanceof Number && ((Number) writeEpoch).longValue() >= 0L
                && "VALID".equals(manifest.getString("validationResult")) && Objects.nonNull(manifest.getDate("validatedAt"));
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

    private static Document publicationFilter(PublishGenerationCommand command) {
        Document filter = new Document(REPOSITORY_ID, command.repositoryId().value());
        command.expectedParent().ifPresentOrElse(pointer -> pointerMatch(filter, CURRENT_POINTER + ".", pointer), () -> requireNoCurrentPointer(filter));
        return filter;
    }

    private static void requireNoCurrentPointer(Document filter) {
        filter.append(CURRENT_POINTER, new Document("$exists", false));
    }

    private static Document publicationUpdate(PublishGenerationCommand command, Date publishedAt) {
        Document set = new Document(CURRENT_POINTER, pointerDocument(new PublishedGenerationPointer(command.targetRevision(), command.targetGenerationId(),
                command.sealedManifestDigest(), command.jobId(), publishedAt.toInstant())));
        Document update = new Document("$set", set);
        command.expectedParent().ifPresentOrElse(pointer -> set.append(ROLLBACK_POINTER, pointerDocument(pointer)),
                () -> update.append("$unset", new Document(ROLLBACK_POINTER, "")));
        return update;
    }

    private static Document rollbackUpdate(RollbackGenerationCommand command, Date publishedAt) {
        Document set = new Document(CURRENT_POINTER, pointerDocument(new PublishedGenerationPointer(command.expectedRollback().revision(),
                command.expectedRollback().generationId(), command.expectedRollback().manifestDigest(), command.jobId(), publishedAt.toInstant())));
        set.append(ROLLBACK_POINTER, pointerDocument(command.expectedCurrent()));
        return new Document("$set", set);
    }

    private static Document pointerMatch(Document filter, String prefix, PublishedGenerationPointer pointer) {
        return filter.append(prefix + REVISION, pointer.revision().value()).append(prefix + GENERATION_ID, pointer.generationId().value())
                .append(prefix + MANIFEST_DIGEST, pointer.manifestDigest().value()).append(prefix + COMMITTED_JOB_ID, pointer.committedJobId())
                .append(prefix + PUBLISHED_AT, Date.from(pointer.publishedAt()));
    }

    private static Document pointerDocument(PublishedGenerationPointer pointer) {
        return new Document(REVISION, pointer.revision().value()).append(GENERATION_ID, pointer.generationId().value())
                .append(MANIFEST_DIGEST, pointer.manifestDigest().value()).append(COMMITTED_JOB_ID, pointer.committedJobId())
                .append(PUBLISHED_AT, Date.from(pointer.publishedAt()));
    }
}
