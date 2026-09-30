package com.java.semantic.query.application;

import com.java.semantic.model.source.SourceEvidenceDocumentCodec;
import com.java.semantic.model.codefact.CodeFactIdentity;
import com.java.semantic.model.codefact.SourceTypeIdentity;
import com.java.semantic.model.git.GitSnapshotId;
import com.java.semantic.model.review.ReviewId;
import com.java.semantic.model.index.IndexCollections;
import com.java.semantic.model.index.IndexSchemaContract;
import com.java.semantic.model.index.ProjectionName;
import com.java.semantic.model.index.ProjectionRequirements;
import com.java.semantic.model.query.SelectedGeneration;
import com.java.semantic.model.source.SourceEvidencePolicy;
import com.java.semantic.model.source.SourceSnapshotMembership;
import com.java.semantic.model.source.ProjectGuideState;
import com.java.semantic.model.source.ProjectGuideMembership;
import com.java.semantic.model.source.SourceCoverage;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.query.config.ConfiguredReadPolicy;
import com.java.semantic.query.config.SearchAccessPlan;
import com.mongodb.MongoException;
import com.mongodb.client.model.Filters;
import org.bson.Document;
import org.springframework.dao.DataAccessException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.HashMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
/** Validates policy and projection compatibility for a generation selected at admission. */
public final class SelectedGenerationGuard {
    static final ProjectionRequirements SOURCES = new ProjectionRequirements(EnumSet.of(ProjectionName.SOURCES, ProjectionName.SYMBOLS));
    static final ProjectionRequirements SYMBOLS = new ProjectionRequirements(EnumSet.of(ProjectionName.SYMBOLS));
    static final ProjectionRequirements RELATIONS = new ProjectionRequirements(EnumSet.of(ProjectionName.RELATIONS, ProjectionName.SYMBOLS));
    static final ProjectionRequirements ENTRY_POINTS = new ProjectionRequirements(EnumSet.of(ProjectionName.ENTRY_POINTS, ProjectionName.SYMBOLS));
    static final ProjectionRequirements SEARCH = new ProjectionRequirements(EnumSet.of(ProjectionName.SEARCH, ProjectionName.SYMBOLS));
    static final ProjectionRequirements SEARCH_WITH_SOURCES = new ProjectionRequirements(EnumSet.of(ProjectionName.SEARCH,
            ProjectionName.SOURCES, ProjectionName.SYMBOLS));
    static final ProjectionRequirements ALL_PROJECTIONS = new ProjectionRequirements(EnumSet.allOf(ProjectionName.class));

    private final MongoTemplate template;
    private final ConfiguredReadPolicy readPolicy;
    private final Duration storageTimeout;

    public SelectedGenerationGuard(MongoTemplate template, ConfiguredReadPolicy readPolicy, Duration storageTimeout) {
        this.template = Objects.requireNonNull(template, "mongo template is required");
        this.readPolicy = Objects.requireNonNull(readPolicy, "read policy is required");
        this.storageTimeout = Objects.requireNonNull(storageTimeout, "storage timeout is required");
    }

    public void requireRepositoryVisible(SelectedGeneration context) {
        SelectedGeneration selected = Objects.requireNonNull(context, "selected generation is required");
        if (!readPolicy.isRepositoryVisible(selected.repositoryId())) {
            throw new RepositoryNotFoundException();
        }
    }

    public SelectedGeneration require(SelectedGeneration context, ProjectionRequirements requirements) {
        requireSourceContext(context, requirements);
        return context;
    }

    SourceContext requireSourceContext(SelectedGeneration context, ProjectionRequirements requirements) {
        SelectedGeneration selected = Objects.requireNonNull(context, "selected generation is required");
        ProjectionRequirements requiredRequirements = Objects.requireNonNull(requirements, "projection requirements are required");
        if (!readPolicy.isRepositoryVisible(selected.repositoryId())) {
            throw new RepositoryNotFoundException();
        }
        try {
            Document manifest = template.getCollection(IndexCollections.GENERATION_MANIFESTS).find(Filters.and(
                            Filters.eq("repoId", selected.repositoryId().value()), Filters.eq("generationId", selected.generationId().value()),
                            Filters.eq("sourceRevision", selected.revision().value()), Filters.eq("identityDigest", selected.manifestDigest().value()),
                            Filters.eq("writeState", "SEALED_VALID"))).maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS).first();
            return validatedSource(selected, manifest, requiredRequirements);
        } catch (MongoException | DataAccessException exception) {
            throw new SemanticIndexUnavailableException(exception);
        } catch (IndexContractMismatchException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IndexContractMismatchException();
        }
    }

    public SearchAccessPlan searchAccessPlan(String requestedRepositoryId) {
        return readPolicy.searchAccessPlan(new RepositoryId(requestedRepositoryId));
    }

    public void requireSourceVisible(SelectedGeneration context, SourceTypeIdentity sourceType) {
        SelectedGeneration selected = Objects.requireNonNull(context, "selected generation is required");
        SourceTypeIdentity identity = Objects.requireNonNull(sourceType, "source type identity is required");
        if (!readPolicy.isSourceVisible(selected.repositoryId(), identity)) {
            throw new RepositoryNotFoundException();
        }
    }

    void requireCodeSource(SelectedGeneration selected, SourceContext source, String path, String contentHash) {
        try {
            SourceSnapshotMembership snapshot = source.snapshot();
            if (!selected.equals(source.selected()) || !source.policy().allowsCode(path)) {
                throw new IndexContractMismatchException();
            }
            Document row = template.getCollection(IndexCollections.GIT_SNAPSHOT_FILES).find(Filters.and(
                    Filters.eq("repoId", selected.repositoryId().value()),
                    Filters.eq("snapshotId", snapshot.snapshotId().value()),
                    Filters.eq("path", path), Filters.eq("contentKind", "CODE"),
                    Filters.eq("contentStatus", "TEXT"), Filters.eq("policyFingerprint", snapshot.policyFingerprint()),
                    Filters.eq("checksum", contentHash))).maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS).first();
            if (Objects.isNull(row) || !List.of("100644", "100755").contains(row.getString("mode"))) {
                throw new IndexContractMismatchException();
            }
        } catch (MongoException | DataAccessException exception) {
            throw new SemanticIndexUnavailableException(exception);
        } catch (IndexContractMismatchException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IndexContractMismatchException();
        }
    }

    void requireReviewSnapshot(SelectedGeneration selected, GitSnapshotId snapshotId, ReviewId reviewId, String ownerJobId) {
        try {
            Document generation = template.getCollection(IndexCollections.GENERATION_MANIFESTS).find(Filters.and(
                    Filters.eq("repoId", selected.repositoryId().value()),
                    Filters.eq("generationId", selected.generationId().value()),
                    Filters.eq("sourceRevision", selected.revision().value()),
                    Filters.eq("identityDigest", selected.manifestDigest().value()),
                    Filters.eq("schemaVersion", IndexSchemaContract.SCHEMA_VERSION),
                    Filters.eq("writeState", "SEALED_VALID")))
                    .maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS).first();
            if (Objects.isNull(generation)) {
                throw new IndexContractMismatchException();
            }
            SourceSnapshotMembership source = template.getConverter().read(SourceSnapshotMembership.class,
                    generation.get("sourceSnapshot", Document.class));
            Document reviewSnapshot = template.getCollection(IndexCollections.GIT_EVIDENCE_MANIFESTS).find(Filters.and(
                    Filters.eq("repoId", selected.repositoryId().value()),
                    Filters.eq("evidenceId", snapshotId.value()), Filters.eq("kind", "SNAPSHOT"),
                    Filters.eq("state", "READY"), Filters.eq("scope", "REVIEW"),
                    Filters.eq("gitEvidenceVersion", IndexSchemaContract.GIT_EVIDENCE_VERSION),
                    Filters.eq("reviewId", reviewId.value()), Filters.eq("ownerJobId", ownerJobId),
                    Filters.eq("revision", selected.revision().value()),
                    Filters.eq("sourceGenerationId", selected.generationId().value()),
                    Filters.eq("policyFingerprint", source.policyFingerprint()),
                    Filters.eq("contentDigest", source.contentDigest())))
                    .maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS).first();
            if (Objects.isNull(reviewSnapshot)
                    || !Objects.equals(generation.get("projectGuide"), reviewSnapshot.get("projectGuide"))) {
                throw new IndexContractMismatchException();
            }
        } catch (MongoException | DataAccessException exception) {
            throw new SemanticIndexUnavailableException(exception);
        } catch (IndexContractMismatchException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IndexContractMismatchException();
        }
    }

    public void requireVisible(SelectedGeneration context, CodeFactIdentity codeFact) {
        SelectedGeneration selected = Objects.requireNonNull(context, "selected generation is required");
        CodeFactIdentity identity = Objects.requireNonNull(codeFact, "code fact identity is required");
        if (!readPolicy.isCodeFactVisible(selected.repositoryId(), identity)) {
            throw new RepositoryNotFoundException();
        }
    }

    boolean isRepositoryVisible(RepositoryId repositoryId) {
        return readPolicy.isRepositoryVisible(repositoryId);
    }

    private SourceContext validatedSource(SelectedGeneration selected, Document manifest, ProjectionRequirements requirements) {
        if (Objects.isNull(manifest) || !Integer.valueOf(IndexSchemaContract.SCHEMA_VERSION).equals(manifest.get("schemaVersion"))) {
            throw new IndexContractMismatchException();
        }
        Object storedVersions = manifest.get("projectionVersions");
        if (!(storedVersions instanceof List<?> versions)) {
            throw new IndexContractMismatchException();
        }
        Map<String, Integer> actual = new HashMap<>();
        for (Object value : versions) {
            if (!(value instanceof Document version)) {
                throw new IndexContractMismatchException();
            }
            Object name = version.get("name");
            Object number = version.get("version");
            if (!(name instanceof String projectionName) || !StringUtils.hasText(projectionName) || !(number instanceof Integer projectionVersion)) {
                throw new IndexContractMismatchException();
            }
            actual.put(projectionName, projectionVersion);
        }
        for (ProjectionName required : requirements.names()) {
            if (!Objects.equals(IndexSchemaContract.requiredProjectionVersions().get(required.name()), actual.get(required.name()))) {
                throw new IndexContractMismatchException();
            }
        }
        try {
            Document snapshotValue = manifest.get("sourceSnapshot", Document.class);
            Document policyValue = manifest.get("sourcePolicy", Document.class);
            Document guideValue = manifest.get("projectGuide", Document.class);
            Document coverageValue = manifest.get("coverage", Document.class);
            Document structureValue = manifest.get("structure", Document.class);
            if (Objects.isNull(snapshotValue) || Objects.isNull(policyValue) || Objects.isNull(guideValue)
                    || Objects.isNull(coverageValue) || Objects.isNull(structureValue)) {
                throw new IndexContractMismatchException();
            }
            SourceSnapshotMembership snapshot = template.getConverter().read(SourceSnapshotMembership.class, snapshotValue);
            SourceEvidencePolicy policy = SourceEvidenceDocumentCodec.decodePolicy(policyValue);
            ProjectGuideMembership guide = SourceEvidenceDocumentCodec.decodeGuide(guideValue);
            String fingerprint = policy.fingerprint();
            template.getConverter().read(SourceCoverage.class, coverageValue);
            SourceEvidenceDocumentCodec.decodeStructure(structureValue);
            Document evidence = template.getCollection(IndexCollections.GIT_EVIDENCE_MANIFESTS).find(Filters.and(
                    Filters.eq("repoId", manifest.getString("repoId")),
                    Filters.eq("evidenceId", snapshot.snapshotId().value()), Filters.eq("kind", "SNAPSHOT"),
                    Filters.eq("state", "READY"), Filters.eq("revision", manifest.getString("sourceRevision")),
                    Filters.eq("gitEvidenceVersion", IndexSchemaContract.GIT_EVIDENCE_VERSION),
                    Filters.eq("sourceGenerationId", manifest.getString("generationId")),
                    Filters.eq("policyFingerprint", snapshot.policyFingerprint()),
                    Filters.eq("contentDigest", snapshot.contentDigest())))
                    .maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS).first();
            if (Objects.isNull(evidence) || !fingerprint.equals(snapshot.policyFingerprint())
                    || !snapshot.revision().value().equals(manifest.getString("sourceRevision"))
                    || guide.path().isPresent() && !guide.path().equals(policy.projectGuidePath())
                    || (guide.state() == ProjectGuideState.DISABLED) != policy.projectGuidePath().isEmpty()
                    || guide.importedRevision().filter(revision -> !revision.equals(snapshot.revision())).isPresent()
                    || guide.provenance().filter(provenance -> !provenance.repositoryId().value().equals(manifest.getString("repoId"))).isPresent()
                    || !Objects.equals(guideValue, evidence.get("projectGuide"))) {
                throw new IndexContractMismatchException();
            }
            return new SourceContext(selected, snapshot, policy, guide, fingerprint);
        } catch (MongoException | DataAccessException storageFailure) {
            throw storageFailure;
        } catch (RuntimeException exception) {
            throw new IndexContractMismatchException();
        }
    }

    record SourceContext(SelectedGeneration selected, SourceSnapshotMembership snapshot, SourceEvidencePolicy policy,
            ProjectGuideMembership guide, String fingerprint) { }
}
