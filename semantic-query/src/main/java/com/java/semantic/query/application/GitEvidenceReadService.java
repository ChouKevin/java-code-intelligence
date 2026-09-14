package com.java.semantic.query.application;

import com.java.semantic.model.git.GitEvidenceId;
import com.java.semantic.model.index.IndexCollections;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.query.config.ConfiguredReadPolicy;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.mongodb.MongoException;
import org.bson.Document;
import org.springframework.dao.DataAccessException;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Mongo-only reader for immutable Git catalog and history manifests. */
public final class GitEvidenceReadService {
    private final MongoTemplate template;
    private final ConfiguredReadPolicy readPolicy;
    private final Duration storageTimeout;

    public GitEvidenceReadService(MongoTemplate template, ConfiguredReadPolicy readPolicy, Duration storageTimeout) {
        this.template = Objects.requireNonNull(template, "mongo template is required");
        this.readPolicy = Objects.requireNonNull(readPolicy, "read policy is required");
        this.storageTimeout = Objects.requireNonNull(storageTimeout, "storage timeout is required");
    }

    public SemanticQueryContract.GitBranchCollection branches(SemanticQueryContract.GitBranchRequest request) {
        SemanticQueryContract.GitBranchRequest required = Objects.requireNonNull(request, "git branch request is required");
        RepositoryId repositoryId = new RepositoryId(required.repositoryId());
        try {
            authorize(repositoryId);
            Document manifest = catalogManifest(repositoryId, required.catalogId());
            long total = requiredLong(manifest, "total");
            List<SemanticQueryContract.GitBranchItem> rows = new ArrayList<>();
            for (Document row : template.getCollection(IndexCollections.GIT_BRANCHES).find(Filters.and(
                    Filters.eq("repoId", repositoryId.value()), Filters.eq("catalogId", manifest.getString("evidenceId"))))
                    .sort(Sorts.ascending("ordinal")).skip(required.offset()).limit(required.limit()).maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                rows.add(new SemanticQueryContract.GitBranchItem(requiredText(row, "branch"), requiredText(row, "head")));
            }
            return new SemanticQueryContract.GitBranchCollection(repositoryId.value(), manifest.getString("evidenceId"),
                    instant(manifest, "observedAt"), List.copyOf(rows), page(required.offset(), required.limit(), rows.size(), total));
        } catch (MongoException | DataAccessException exception) {
            throw new SemanticIndexUnavailableException(exception);
        } catch (RepositoryNotFoundException | GitEvidenceNotFoundException | GitEvidenceNotReadyException | IndexContractMismatchException | IllegalArgumentException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IndexContractMismatchException();
        }
    }

    public SemanticQueryContract.GitCommitCollection commits(SemanticQueryContract.GitCommitRequest request) {
        SemanticQueryContract.GitCommitRequest required = Objects.requireNonNull(request, "git commit request is required");
        RepositoryId repositoryId = new RepositoryId(required.repositoryId());
        try {
            authorize(repositoryId);
            Document manifest = historyManifest(repositoryId, new GitEvidenceId(required.historyId()));
            if (!required.revision().equals(requiredText(manifest, "revision"))) {
                throw new IllegalArgumentException("history revision does not match the requested revision");
            }
            long total = requiredLong(manifest, "total");
            List<SemanticQueryContract.GitCommitItem> rows = new ArrayList<>();
            for (Document row : template.getCollection(IndexCollections.GIT_COMMITS).find(Filters.and(
                    Filters.eq("repoId", repositoryId.value()), Filters.eq("historyId", required.historyId())))
                    .sort(Sorts.ascending("ordinal")).skip(required.offset()).limit(required.limit()).maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                List<String> parents = row.getList("parents", String.class, List.of());
                rows.add(new SemanticQueryContract.GitCommitItem(requiredText(row, "revision"), List.copyOf(parents),
                        requiredString(row, "subject"), instant(row, "committedAt")));
            }
            return new SemanticQueryContract.GitCommitCollection(repositoryId.value(), required.historyId(), required.revision(),
                    instant(manifest, "preparedAt"), List.copyOf(rows), page(required.offset(), required.limit(), rows.size(), total));
        } catch (MongoException | DataAccessException exception) {
            throw new SemanticIndexUnavailableException(exception);
        } catch (RepositoryNotFoundException | GitEvidenceNotFoundException | GitEvidenceNotReadyException | IndexContractMismatchException | IllegalArgumentException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IndexContractMismatchException();
        }
    }

    private void authorize(RepositoryId repositoryId) {
        readPolicy.requireGitEvidenceVisible(repositoryId);
    }

    private Document catalogManifest(RepositoryId repositoryId, Optional<String> catalogId) {
        Document manifest = catalogId.map(value -> findManifest(repositoryId, new GitEvidenceId(value))).orElseGet(() -> template
                .getCollection(IndexCollections.GIT_EVIDENCE_MANIFESTS).find(Filters.and(Filters.eq("repoId", repositoryId.value()),
                        Filters.eq("kind", "CATALOG"), Filters.eq("state", "READY"))).sort(Sorts.descending("observedAt")).maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS).first());
        return ready(manifest, "CATALOG");
    }

    private Document historyManifest(RepositoryId repositoryId, GitEvidenceId historyId) {
        return ready(findManifest(repositoryId, historyId), "HISTORY");
    }

    private Document findManifest(RepositoryId repositoryId, GitEvidenceId evidenceId) {
        return template.getCollection(IndexCollections.GIT_EVIDENCE_MANIFESTS).find(Filters.and(Filters.eq("repoId", repositoryId.value()),
                Filters.eq("evidenceId", evidenceId.value()))).maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS).first();
    }

    private static Document ready(Document manifest, String kind) {
        if (Objects.isNull(manifest)) {
            throw new GitEvidenceNotFoundException();
        }
        if (!kind.equals(manifest.getString("kind"))) {
            throw new IllegalArgumentException("git evidence kind does not match request");
        }
        if (!"READY".equals(manifest.getString("state"))) {
            throw new GitEvidenceNotReadyException();
        }
        if (manifest.getInteger("gitEvidenceVersion", 0) != 1) {
            throw new IndexContractMismatchException();
        }
        return manifest;
    }

    private static SemanticQueryContract.Page page(int offset, int limit, int returned, long total) {
        return new SemanticQueryContract.Page(offset, limit, returned, total, offset + returned < total);
    }
    private static String requiredText(Document document, String field) {
        String value = document.getString(field);
        if (Objects.isNull(value) || value.isBlank()) { throw new IndexContractMismatchException(); }
        return value;
    }
    private static String requiredString(Document document, String field) {
        String value = document.getString(field);
        if (Objects.isNull(value)) { throw new IndexContractMismatchException(); }
        return value;
    }
    private static long requiredLong(Document document, String field) {
        Number value = document.get(field, Number.class);
        if (Objects.isNull(value) || value.longValue() < 0) { throw new IndexContractMismatchException(); }
        return value.longValue();
    }
    private static Instant instant(Document document, String field) {
        java.util.Date value = document.getDate(field);
        if (Objects.isNull(value)) { throw new IndexContractMismatchException(); }
        return value.toInstant();
    }
}
