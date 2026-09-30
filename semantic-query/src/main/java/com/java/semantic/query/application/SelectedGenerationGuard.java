package com.java.semantic.query.application;

import com.java.semantic.model.source.SourceEvidenceDocumentCodec;
import com.java.semantic.model.codefact.CodeFactIdentity;
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
import com.java.semantic.model.source.SourceStructure;
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
import java.util.ArrayList;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
/** Validates policy and projection compatibility for a generation selected at admission. */
public final class SelectedGenerationGuard {
    static final ProjectionRequirements SOURCES = new ProjectionRequirements(EnumSet.of(ProjectionName.SOURCES, ProjectionName.SYMBOLS));
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


    SourceContext requireReviewSnapshot(SourceContext source, GitSnapshotId snapshotId, ReviewId reviewId, String ownerJobId) {
        SelectedGeneration selected = source.selected();
        try {
            Document reviewSnapshot = template.getCollection(IndexCollections.GIT_EVIDENCE_MANIFESTS).find(Filters.and(
                    Filters.eq("repoId", selected.repositoryId().value()),
                    Filters.eq("evidenceId", snapshotId.value()), Filters.eq("kind", "SNAPSHOT"),
                    Filters.eq("state", "READY"), Filters.eq("scope", "REVIEW"),
                    Filters.eq("gitEvidenceVersion", IndexSchemaContract.GIT_EVIDENCE_VERSION),
                    Filters.eq("reviewId", reviewId.value()), Filters.eq("ownerJobId", ownerJobId),
                    Filters.eq("revision", selected.revision().value()),
                    Filters.eq("sourceGenerationId", selected.generationId().value()),
                    Filters.eq("policyFingerprint", source.snapshot().policyFingerprint()),
                    Filters.eq("contentDigest", source.snapshot().contentDigest())))
                    .maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS).first();
            if (Objects.isNull(reviewSnapshot)
                    || !Objects.equals(new Document(SourceEvidenceDocumentCodec.encodeGuide(source.guide())), reviewSnapshot.get("projectGuide"))) {
                throw new IndexContractMismatchException();
            }
            SourceSnapshotMembership membership = new SourceSnapshotMembership(snapshotId, selected.revision(),
                    source.snapshot().policyFingerprint(), source.snapshot().contentDigest());
            return new SourceContext(selected, membership, source.policy(), source.guide(), source.fingerprint(),
                    source.coverage(), source.structure(), source.projectionVersions(), SnapshotEvidence.decode(reviewSnapshot), source.modules());
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
            SourceCoverage coverage = new SourceCoverage(count(coverageValue, "readableCode"),
                    count(coverageValue, "excludedOrUnsupported"), count(coverageValue, "extractionIssues"),
                    count(coverageValue, "unresolvedSemanticEvidence"));
            SourceStructure structure = SourceEvidenceDocumentCodec.decodeStructure(structureValue);
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
            return new SourceContext(selected, snapshot, policy, guide, fingerprint, coverage, structure,
                    actual, SnapshotEvidence.decode(evidence), modules(manifest, structure));
        } catch (MongoException | DataAccessException storageFailure) {
            throw storageFailure;
        } catch (RuntimeException exception) {
            throw new IndexContractMismatchException();
        }
    }

    private static long count(Document document, String field) {
        Object value = document.get(field);
        if (!(value instanceof Long || value instanceof Integer) || ((Number) value).longValue() < 0) {
            throw new IndexContractMismatchException();
        }
        return ((Number) value).longValue();
    }

    private static Optional<List<SourceModule>> modules(Document manifest, SourceStructure structure) {
        Document inputs = manifest.get("analysisInputs", Document.class);
        if (Objects.isNull(inputs)) return Optional.empty();
        if (!Integer.valueOf(IndexSchemaContract.ANALYSIS_EVIDENCE_VERSION).equals(inputs.get("contractVersion"))
                || !(inputs.get("projects") instanceof List<?> projects)) {
            throw new IndexContractMismatchException();
        }
        Set<String> includedRoots = Set.copyOf(structure.importedSourceRoots());
        List<SourceModule> modules = new ArrayList<>(projects.size());
        for (Object value : projects) {
            if (!(value instanceof Document project) || !(project.get("roots") instanceof List<?> roots)) {
                throw new IndexContractMismatchException();
            }
            String path = project.getString("projectPath");
            if (!SourceEvidencePolicy.validPath(path)) throw new IndexContractMismatchException();
            List<String> sourceRoots = new ArrayList<>();
            for (Object rootValue : roots) {
                if (!(rootValue instanceof Document root) || !(root.get("included") instanceof Boolean included)) {
                    throw new IndexContractMismatchException();
                }
                String rootPath = root.getString("path");
                if (!SourceEvidencePolicy.validPath(rootPath)) throw new IndexContractMismatchException();
                if (included && includedRoots.contains(rootPath)) sourceRoots.add(rootPath);
            }
            if (!sourceRoots.isEmpty()) modules.add(new SourceModule(path, sourceRoots.stream().distinct().sorted().toList()));
        }
        return Optional.of(List.copyOf(modules));
    }

    public record SourceModule(String path, List<String> sourceRoots) {
        public SourceModule { sourceRoots = List.copyOf(sourceRoots); }
    }

    public record SourceContext(SelectedGeneration selected, SourceSnapshotMembership snapshot, SourceEvidencePolicy policy,
            ProjectGuideMembership guide, String fingerprint, SourceCoverage coverage, SourceStructure structure,
            Map<String, Integer> projectionVersions, SnapshotEvidence evidence, Optional<List<SourceModule>> modules) {
        public SourceContext {
            projectionVersions = Map.copyOf(projectionVersions);
            modules = modules.map(List::copyOf);
        }

        public void requireProjections(ProjectionRequirements requirements) {
            for (ProjectionName required : requirements.names()) {
                if (!Objects.equals(IndexSchemaContract.requiredProjectionVersions().get(required.name()),
                        projectionVersions.get(required.name()))) {
                    throw new IndexContractMismatchException();
                }
            }
        }
    }
}
