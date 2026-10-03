package com.java.semantic.indexer.build;

import com.java.semantic.indexer.job.IndexJob;
import com.java.semantic.indexer.store.GitEvidencePublicationStore;
import com.java.semantic.model.git.GitFileContentStatus;
import com.java.semantic.model.git.GitSnapshotEntry;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.model.source.ProjectGuideMembership;
import com.java.semantic.model.source.SourceEvidencePolicy;
import com.java.semantic.model.source.SourceSnapshotMembership;
import com.java.semantic.model.source.TrackedSourceInventory;
import com.java.semantic.repository.port.GitRepositoryPort;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Seals exact Git source evidence before the semantic generation becomes publishable. */
public final class SourceSnapshotPublication {
    private final GitRepositoryPort git;
    private final GitEvidencePublicationStore evidence;
    private final ProjectGuideReader guides;
    private final long maxGuideBytes;

    public SourceSnapshotPublication(GitRepositoryPort git, GitEvidencePublicationStore evidence, long maxGuideBytes) {
        this.git = Objects.requireNonNull(git, "Git adapter is required");
        this.evidence = Objects.requireNonNull(evidence, "Git evidence publication is required");
        this.guides = new ProjectGuideReader();
        this.maxGuideBytes = maxGuideBytes;
    }

    public PublishedSource publish(IndexJob job, Path root, RepositoryRevision revision, FullIndexPlan plan,
            Optional<String> configuredGuidePath) {
        SourceEvidencePolicy policy = ImportedSourcePolicy.from(plan, configuredGuidePath);
        TrackedSourceInventory inventory = git.prepareSnapshot(root, revision, policy);
        List<GitSnapshotEntry> candidates = inventory.candidates();
        Optional<GitSnapshotEntry> candidate = configuredGuidePath.flatMap(path -> candidates.stream()
                .filter(entry -> path.equals(entry.path())).findFirst());
        ProjectGuideMembership guide = guides.read(job.repositoryId(), revision, configuredGuidePath, candidate, maxGuideBytes);
        List<GitSnapshotEntry> authorized = candidates.stream()
                .filter(entry -> entry.contentStatus() == GitFileContentStatus.TEXT)
                .filter(entry -> policy.allowsCode(entry.path()) || guide.path().filter(entry.path()::equals).isPresent())
                .toList();
        Map<String, GitSnapshotEntry> authorizedByPath = HashMap.newHashMap(authorized.size());
        for (GitSnapshotEntry entry : authorized) {
            authorizedByPath.putIfAbsent(entry.path(), entry);
        }
        for (FullIndexPlan.SourceInput source : plan.sources()) {
            GitSnapshotEntry entry = authorizedByPath.get(source.sourcePath());
            if (Objects.isNull(entry)) {
                throw new IllegalStateException("selected source is missing from the exact Git tree");
            }
            String exactHash = com.java.semantic.model.index.SourceArtifactDocument.create(
                    new String(entry.bytes(), java.nio.charset.StandardCharsets.UTF_8)).contentHash();
            if (!source.contentArtifact().contentHash().equals(exactHash)) {
                throw new IllegalStateException("selected source differs from the exact Git blob");
            }
        }
        SourceSnapshotMembership snapshot = evidence.publishSourceSnapshot(job, revision, authorized, policy, guide, Instant.now());
        return new PublishedSource(snapshot, guide, policy, inventory.excludedOrUnsupported()
                + candidates.size() - authorized.size());
    }

    public record PublishedSource(SourceSnapshotMembership snapshot, ProjectGuideMembership guide,
            SourceEvidencePolicy policy, long excludedOrUnsupported) { }
}
