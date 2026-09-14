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
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Mongo-only reader for immutable Git catalog and history manifests. */
public final class GitEvidenceReadService {
    private static final int MAX_CURSOR_CHARACTERS = 2048;
    private static final String DIFF_CURSOR_OPERATION = "git-file-diff";
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

    public SemanticQueryContract.GitComparisonCollection comparisons(SemanticQueryContract.GitComparisonRequest request) {
        SemanticQueryContract.GitComparisonRequest required = Objects.requireNonNull(request, "git comparison request is required");
        RepositoryId repositoryId = new RepositoryId(required.repositoryId());
        try {
            authorize(repositoryId);
            Document manifest = comparisonManifest(repositoryId, new GitEvidenceId(required.comparisonId()));
            if (!required.previous().equals(requiredText(manifest, "previous")) || !required.current().equals(requiredText(manifest, "current"))) {
                throw new IllegalArgumentException("comparison endpoints do not match the requested revisions");
            }
            List<SemanticQueryContract.GitChangeItem> allChanges = validateComparisonChanges(repositoryId, required.comparisonId(), manifest);
            long total = allChanges.size();
            int pageStart = Math.min(required.offset(), allChanges.size());
            int pageEnd = Math.min(pageStart + required.limit(), allChanges.size());
            List<SemanticQueryContract.GitChangeItem> changes = List.copyOf(allChanges.subList(pageStart, pageEnd));
            return new SemanticQueryContract.GitComparisonCollection(repositoryId.value(), required.comparisonId(), required.previous(), required.current(),
                    requiredText(manifest, "previousSnapshotId"), requiredText(manifest, "currentSnapshotId"), requiredText(manifest, "ancestry"),
                    List.copyOf(changes), page(required.offset(), required.limit(), changes.size(), total));
        } catch (MongoException | DataAccessException exception) {
            throw new SemanticIndexUnavailableException(exception);
        } catch (RepositoryNotFoundException | GitEvidenceNotFoundException | GitEvidenceNotReadyException | IndexContractMismatchException | IllegalArgumentException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IndexContractMismatchException();
        }
    }

    public SemanticQueryContract.GitFileDiffResult fileDiff(SemanticQueryContract.GitFileDiffRequest request) {
        SemanticQueryContract.GitFileDiffRequest required = Objects.requireNonNull(request, "git file diff request is required");
        RepositoryId repositoryId = new RepositoryId(required.repositoryId());
        try {
            authorize(repositoryId);
            Document manifest = comparisonManifest(repositoryId, new GitEvidenceId(required.comparisonId()));
            if (!required.previous().equals(requiredText(manifest, "previous")) || !required.current().equals(requiredText(manifest, "current"))) {
                throw new IllegalArgumentException("comparison endpoints do not match the requested revisions");
            }
            List<SemanticQueryContract.GitChangeItem> allChanges = validateComparisonChanges(repositoryId, required.comparisonId(), manifest);
            Document row = template.getCollection(IndexCollections.GIT_COMPARISON_CHANGES).find(Filters.and(Filters.eq("repoId", repositoryId.value()),
                    Filters.eq("comparisonId", required.comparisonId()), Filters.eq("changeId", required.changeId()))).maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS).first();
            if (Objects.isNull(row)) {
                throw new IllegalArgumentException("change does not belong to the comparison");
            }
            if (allChanges.stream().noneMatch(change -> change.changeId().equals(required.changeId()))) {
                throw new IndexContractMismatchException();
            }
            long ordinal = required.cursor().map(cursor -> decodeCursor(cursor, repositoryId, required.comparisonId(), required.previous(), required.current(),
                    required.changeId())).orElse(0L);
            PatchPage patchPage = patchPage(repositoryId, required.comparisonId(), required.changeId(), row, ordinal, required.cursor().isPresent());
            return new SemanticQueryContract.GitFileDiffResult(repositoryId.value(), required.comparisonId(), required.previous(), required.current(),
                    change(row), patchPage.text(), patchPage.hasNext() ? Optional.of(encodeCursor(repositoryId, required.comparisonId(), required.previous(),
                            required.current(), required.changeId(), ordinal + 1L)) : Optional.empty());
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

    private Document comparisonManifest(RepositoryId repositoryId, GitEvidenceId comparisonId) {
        Document manifest = ready(findManifest(repositoryId, comparisonId), "COMPARISON");
        readySnapshot(repositoryId, requiredText(manifest, "previousSnapshotId"), requiredText(manifest, "previous"));
        readySnapshot(repositoryId, requiredText(manifest, "currentSnapshotId"), requiredText(manifest, "current"));
        return manifest;
    }

    private void readySnapshot(RepositoryId repositoryId, String snapshotId, String revision) {
        Document snapshot = ready(findManifest(repositoryId, new GitEvidenceId(snapshotId)), "SNAPSHOT");
        if (!revision.equals(requiredText(snapshot, "revision"))) {
            throw new IndexContractMismatchException();
        }
    }

    private List<SemanticQueryContract.GitChangeItem> validateComparisonChanges(RepositoryId repositoryId, String comparisonId, Document manifest) {
        long expectedTotal = requiredLong(manifest, "total");
        List<SemanticQueryContract.GitChangeItem> changes = new ArrayList<>();
        HashSet<String> identifiers = new HashSet<>();
        long ordinal = 0L;
        for (Document row : template.getCollection(IndexCollections.GIT_COMPARISON_CHANGES).find(Filters.and(
                Filters.eq("repoId", repositoryId.value()), Filters.eq("comparisonId", comparisonId))).sort(Sorts.ascending("ordinal"))
                .maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
            Number actualOrdinal = row.get("ordinal", Number.class);
            if (Objects.isNull(actualOrdinal) || actualOrdinal.longValue() != ordinal || !repositoryId.value().equals(requiredText(row, "repoId"))
                    || !comparisonId.equals(requiredText(row, "comparisonId"))) {
                throw new IndexContractMismatchException();
            }
            SemanticQueryContract.GitChangeItem change = change(row);
            if (!identifiers.add(change.changeId())) {
                throw new IndexContractMismatchException();
            }
            validatePatchRows(repositoryId, comparisonId, change.changeId(), row);
            changes.add(change);
            ordinal++;
        }
        if (ordinal != expectedTotal) {
            throw new IndexContractMismatchException();
        }
        return List.copyOf(changes);
    }

    private void validatePatchRows(RepositoryId repositoryId, String comparisonId, String changeId, Document change) {
        long expectedChunks = requiredLong(change, "patchChunkCount");
        String status = requiredText(change, "diffStatus");
        long actualChunks = chunkCount(repositoryId, comparisonId, changeId);
        if (!"AVAILABLE".equals(status)) {
            if (expectedChunks != 0L || actualChunks != 0L) {
                throw new IndexContractMismatchException();
            }
            return;
        }
        if (expectedChunks == 0L || actualChunks != expectedChunks || !contiguousPatchOrdinals(repositoryId, comparisonId, changeId, expectedChunks)) {
            throw new IndexContractMismatchException();
        }
        for (Document chunk : template.getCollection(IndexCollections.GIT_COMPARISON_PATCHES).find(Filters.and(Filters.eq("repoId", repositoryId.value()),
                Filters.eq("comparisonId", comparisonId), Filters.eq("changeId", changeId))).sort(Sorts.ascending("ordinal"))
                .maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
            String patch = requiredString(chunk, "patch");
            if (patch.isEmpty() || patch.getBytes(StandardCharsets.UTF_8).length > 64 * 1024) {
                throw new IndexContractMismatchException();
            }
        }
    }

    private PatchPage patchPage(RepositoryId repositoryId, String comparisonId, String changeId, Document change, long ordinal, boolean hasCursor) {
        long expectedChunks = requiredLong(change, "patchChunkCount");
        String status = requiredText(change, "diffStatus");
        if (!"AVAILABLE".equals(status)) {
            if (expectedChunks != 0L || hasCursor || chunkCount(repositoryId, comparisonId, changeId) != 0L) {
                throw new IndexContractMismatchException();
            }
            return new PatchPage("", false);
        }
        if (expectedChunks == 0L || ordinal >= expectedChunks || chunkCount(repositoryId, comparisonId, changeId) != expectedChunks
                || !contiguousPatchOrdinals(repositoryId, comparisonId, changeId, expectedChunks)) {
            throw new IndexContractMismatchException();
        }
        Document patch = template.getCollection(IndexCollections.GIT_COMPARISON_PATCHES).find(Filters.and(Filters.eq("repoId", repositoryId.value()),
                Filters.eq("comparisonId", comparisonId), Filters.eq("changeId", changeId), Filters.eq("ordinal", ordinal)))
                .maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS).first();
        if (Objects.isNull(patch)) {
            throw new IndexContractMismatchException();
        }
        String text = requiredString(patch, "patch");
        if (text.isEmpty() || text.getBytes(StandardCharsets.UTF_8).length > 64 * 1024) {
            throw new IndexContractMismatchException();
        }
        return new PatchPage(text, ordinal + 1L < expectedChunks);
    }

    private long chunkCount(RepositoryId repositoryId, String comparisonId, String changeId) {
        return template.getCollection(IndexCollections.GIT_COMPARISON_PATCHES).countDocuments(Filters.and(Filters.eq("repoId", repositoryId.value()),
                Filters.eq("comparisonId", comparisonId), Filters.eq("changeId", changeId)));
    }

    private boolean contiguousPatchOrdinals(RepositoryId repositoryId, String comparisonId, String changeId, long expectedChunks) {
        long ordinal = 0L;
        for (Document chunk : template.getCollection(IndexCollections.GIT_COMPARISON_PATCHES).find(Filters.and(Filters.eq("repoId", repositoryId.value()),
                Filters.eq("comparisonId", comparisonId), Filters.eq("changeId", changeId))).projection(new Document("ordinal", 1))
                .sort(Sorts.ascending("ordinal")).maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
            Number actual = chunk.get("ordinal", Number.class);
            if (Objects.isNull(actual) || actual.longValue() != ordinal) {
                return false;
            }
            ordinal++;
        }
        return ordinal == expectedChunks;
    }

    private record PatchPage(String text, boolean hasNext) { }

    private static SemanticQueryContract.GitChangeItem change(Document row) {
        return new SemanticQueryContract.GitChangeItem(requiredText(row, "changeId"), requiredText(row, "kind"), optionalString(row, "oldPath"),
                optionalString(row, "newPath"), optionalString(row, "oldMode"), optionalString(row, "newMode"), optionalString(row, "oldBlobId"),
                optionalString(row, "newBlobId"), requiredText(row, "diffStatus"));
    }

    private static String encodeCursor(RepositoryId repositoryId, String comparisonId, String previous, String current, String changeId, long ordinal) {
        String payload = String.join("\u001f", DIFF_CURSOR_OPERATION, repositoryId.value(), comparisonId, previous, current, changeId, Long.toString(ordinal));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    private static long decodeCursor(String cursor, RepositoryId repositoryId, String comparisonId, String previous, String current, String changeId) {
        try {
            if (cursor.length() > MAX_CURSOR_CHARACTERS) {
                throw new IllegalArgumentException("diff cursor is invalid");
            }
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            if (decoded.length() > MAX_CURSOR_CHARACTERS) {
                throw new IllegalArgumentException("diff cursor is invalid");
            }
            String[] values = decoded.split("\u001f", -1);
            if (values.length != 7 || !DIFF_CURSOR_OPERATION.equals(values[0]) || !repositoryId.value().equals(values[1])
                    || !comparisonId.equals(values[2]) || !previous.equals(values[3]) || !current.equals(values[4]) || !changeId.equals(values[5])) {
                throw new IllegalArgumentException("diff cursor is invalid");
            }
            long ordinal = Long.parseLong(values[6]);
            if (ordinal < 0L) { throw new IllegalArgumentException("diff cursor is invalid"); }
            return ordinal;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("diff cursor is invalid", exception);
        }
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
    private static String optionalString(Document document, String field) { return Objects.requireNonNullElse(document.getString(field), ""); }
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
