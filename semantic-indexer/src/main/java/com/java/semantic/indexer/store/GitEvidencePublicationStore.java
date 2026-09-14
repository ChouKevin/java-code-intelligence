package com.java.semantic.indexer.store;

import com.java.semantic.indexer.job.IndexJob;
import com.java.semantic.model.git.GitBranch;
import com.java.semantic.model.git.GitCatalogManifest;
import com.java.semantic.model.git.GitCommit;
import com.java.semantic.model.git.GitEvidenceId;
import com.java.semantic.model.git.GitEvidenceState;
import com.java.semantic.model.git.GitHistoryManifest;
import com.java.semantic.model.index.IndexCollections;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Writes immutable Git rows first and makes them visible only through a final READY manifest transition. */
@Component
public final class GitEvidencePublicationStore {
    private final MongoTemplate template;

    public GitEvidencePublicationStore(MongoTemplate template) {
        this.template = Objects.requireNonNull(template, "mongo template is required");
    }

    public GitCatalogManifest beginCatalog(IndexJob job, Instant observedAt) {
        verifySchemaBeforeEvidence();
        GitEvidenceId id = GitEvidenceId.create();
        GitCatalogManifest manifest = new GitCatalogManifest(id, job.repositoryId(), observedAt, GitEvidenceState.PREPARING, GitCatalogManifest.VERSION);
        template.getCollection(IndexCollections.GIT_EVIDENCE_MANIFESTS).insertOne(new Document("repoId", job.repositoryId().value())
                .append("evidenceId", id.value()).append("kind", "CATALOG").append("state", "PREPARING")
                .append("gitEvidenceVersion", GitCatalogManifest.VERSION).append("observedAt", java.util.Date.from(observedAt))
                .append("ownerJobId", job.id().value()).append("total", 0L));
        bind(job, id);
        return manifest;
    }

    public GitHistoryManifest beginHistory(IndexJob job, GitEvidenceId catalogId, String branch, RepositoryRevision revision, Instant preparedAt) {
        verifySchemaBeforeEvidence();
        GitEvidenceId id = GitEvidenceId.create();
        GitHistoryManifest manifest = new GitHistoryManifest(id, catalogId, job.repositoryId(), branch, revision, preparedAt,
                GitEvidenceState.PREPARING, GitHistoryManifest.VERSION, 0L);
        template.getCollection(IndexCollections.GIT_EVIDENCE_MANIFESTS).insertOne(new Document("repoId", job.repositoryId().value())
                .append("evidenceId", id.value()).append("kind", "HISTORY").append("state", "PREPARING")
                .append("gitEvidenceVersion", GitHistoryManifest.VERSION).append("catalogId", catalogId.value()).append("branch", branch)
                .append("revision", revision.value()).append("preparedAt", java.util.Date.from(preparedAt)).append("ownerJobId", job.id().value()).append("total", 0L));
        bind(job, id);
        return manifest;
    }

    public void appendBranches(GitCatalogManifest manifest, List<GitBranch> branches) {
        long ordinal = 0L;
        for (GitBranch branch : branches) {
            template.getCollection(IndexCollections.GIT_BRANCHES).insertOne(new Document("repoId", manifest.repositoryId().value())
                    .append("catalogId", manifest.catalogId().value()).append("ordinal", ordinal++).append("branch", branch.name())
                    .append("head", branch.head().value()));
        }
        ready(manifest.repositoryId(), manifest.catalogId(), branches.size());
    }

    public void appendCommit(GitHistoryManifest manifest, long ordinal, GitCommit commit) {
        List<String> parents = commit.parents().stream().map(RepositoryRevision::value).toList();
        template.getCollection(IndexCollections.GIT_COMMITS).insertOne(new Document("repoId", manifest.repositoryId().value())
                .append("historyId", manifest.historyId().value()).append("ordinal", ordinal).append("revision", commit.revision().value())
                .append("parents", parents).append("subject", commit.subject()).append("committedAt", java.util.Date.from(commit.committedAt())));
    }

    public void readyHistory(GitHistoryManifest manifest, long total) {
        ready(manifest.repositoryId(), manifest.historyId(), total);
    }

    public boolean catalogContainsHead(RepositoryId repositoryId, GitEvidenceId catalogId, String branch, RepositoryRevision revision) {
        Document manifest = template.getCollection(IndexCollections.GIT_EVIDENCE_MANIFESTS).find(Filters.and(Filters.eq("repoId", repositoryId.value()),
                Filters.eq("evidenceId", catalogId.value()), Filters.eq("kind", "CATALOG"), Filters.eq("state", "READY"))).first();
        if (Objects.isNull(manifest)) { return false; }
        Document row = template.getCollection(IndexCollections.GIT_BRANCHES).find(Filters.and(Filters.eq("repoId", repositoryId.value()),
                Filters.eq("catalogId", catalogId.value()), Filters.eq("branch", branch), Filters.eq("head", revision.value()))).first();
        return Objects.nonNull(row);
    }

    public void fail(IndexJob job) {
        template.getCollection(IndexCollections.GIT_EVIDENCE_MANIFESTS).updateMany(Filters.and(Filters.eq("repoId", job.repositoryId().value()),
                Filters.eq("ownerJobId", job.id().value()), Filters.eq("state", "PREPARING")), Updates.set("state", "FAILED"));
    }

    private void bind(IndexJob job, GitEvidenceId evidenceId) {
        template.getCollection(IndexCollections.INDEX_JOBS).updateOne(Filters.and(Filters.eq("jobId", job.id().value()),
                Filters.eq("repoId", job.repositoryId().value()), Filters.eq("active", true)), Updates.set("gitEvidence.evidenceId", evidenceId.value()));
    }

    private void ready(RepositoryId repositoryId, GitEvidenceId evidenceId, long total) {
        validateRows(repositoryId, evidenceId, total);
        long modified = template.getCollection(IndexCollections.GIT_EVIDENCE_MANIFESTS).updateOne(Filters.and(Filters.eq("repoId", repositoryId.value()),
                Filters.eq("evidenceId", evidenceId.value()), Filters.eq("state", "PREPARING")), Updates.combine(Updates.set("total", total), Updates.set("state", "READY"))).getModifiedCount();
        if (modified != 1L) { throw new PublicationConflictException(); }
    }

    public void verifySchemaBeforeEvidence() {
        new MongoIndexSchemaReadinessVerifier(template).verify();
    }

    private void validateRows(RepositoryId repositoryId, GitEvidenceId evidenceId, long total) {
        Document manifest = template.getCollection(IndexCollections.GIT_EVIDENCE_MANIFESTS).find(Filters.and(
                Filters.eq("repoId", repositoryId.value()), Filters.eq("evidenceId", evidenceId.value()),
                Filters.eq("state", "PREPARING"))).first();
        if (Objects.isNull(manifest)) {
            throw new PublicationConflictException();
        }
        String kind = manifest.getString("kind");
        String collection = "CATALOG".equals(kind) ? IndexCollections.GIT_BRANCHES : IndexCollections.GIT_COMMITS;
        String evidenceField = "CATALOG".equals(kind) ? "catalogId" : "historyId";
        long ordinal = 0L;
        for (Document row : template.getCollection(collection).find(Filters.and(Filters.eq("repoId", repositoryId.value()),
                Filters.eq(evidenceField, evidenceId.value()))).sort(com.mongodb.client.model.Sorts.ascending("ordinal"))) {
            Number storedOrdinal = row.get("ordinal", Number.class);
            if (Objects.isNull(storedOrdinal) || storedOrdinal.longValue() != ordinal) {
                throw new PublicationConflictException();
            }
            validateRow(kind, manifest, row, ordinal);
            ordinal++;
        }
        if (ordinal != total) {
            throw new PublicationConflictException();
        }
    }

    private static void validateRow(String kind, Document manifest, Document row, long ordinal) {
        if ("CATALOG".equals(kind)) {
            if (!hasText(row.getString("branch")) || !isSha(row.getString("head"))) {
                throw new PublicationConflictException();
            }
            return;
        }
        String revision = row.getString("revision");
        List<String> parents = row.getList("parents", String.class, List.of());
        if (!isSha(revision) || parents.stream().anyMatch(parent -> !isSha(parent)) || !hasText(row.getString("subject"))
                || Objects.isNull(row.getDate("committedAt")) || (ordinal == 0L && !revision.equals(manifest.getString("revision")))) {
            throw new PublicationConflictException();
        }
    }

    private static boolean hasText(String value) {
        return Objects.nonNull(value) && !value.isBlank();
    }

    private static boolean isSha(String value) {
        return Objects.nonNull(value) && value.matches("[0-9a-f]{40}");
    }
}
