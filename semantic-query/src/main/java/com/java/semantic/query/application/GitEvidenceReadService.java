package com.java.semantic.query.application;

import com.java.semantic.model.git.GitEvidenceId;
import com.java.semantic.model.git.GitChangeKind;
import com.java.semantic.model.git.GitFileContentStatus;
import com.java.semantic.model.index.IndexCollections;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.query.config.ConfiguredReadPolicy;
import com.mongodb.client.model.CountOptions;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.mongodb.MongoException;
import org.bson.Document;
import org.bson.types.Binary;
import org.springframework.dao.DataAccessException;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.time.Instant;
import java.util.ArrayList;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.util.Arrays;
import java.util.Base64;
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
            long total = validateComparisonLayout(repositoryId, required.comparisonId(), manifest);
            List<SemanticQueryContract.GitChangeItem> changes = comparisonPage(repositoryId, required.comparisonId(), required.offset(), required.limit());
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
            long total = validateComparisonLayout(repositoryId, required.comparisonId(), manifest);
            Document row = template.getCollection(IndexCollections.GIT_COMPARISON_CHANGES).find(Filters.and(Filters.eq("repoId", repositoryId.value()),
                    Filters.eq("comparisonId", required.comparisonId()), Filters.eq("changeId", required.changeId()))).maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS).first();
            if (Objects.isNull(row)) {
                throw new IllegalArgumentException("change does not belong to the comparison");
            }
            if (changeCount(repositoryId, required.comparisonId(), required.changeId()) != 1L || requiredLong(row, "ordinal") >= total) {
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

    private long validateComparisonLayout(RepositoryId repositoryId, String comparisonId, Document manifest) {
        long expectedTotal = requiredLong(manifest, "total");
        long actualTotal = comparisonCount(repositoryId, comparisonId);
        if (actualTotal != expectedTotal) {
            throw new IndexContractMismatchException();
        }
        if (expectedTotal == 0L) {
            return 0L;
        }
        Document first = comparisonBoundary(repositoryId, comparisonId, Sorts.ascending("ordinal"));
        Document last = comparisonBoundary(repositoryId, comparisonId, Sorts.descending("ordinal"));
        if (requiredLong(first, "ordinal") != 0L || requiredLong(last, "ordinal") != expectedTotal - 1L) {
            throw new IndexContractMismatchException();
        }
        return expectedTotal;
    }

    private List<SemanticQueryContract.GitChangeItem> comparisonPage(RepositoryId repositoryId, String comparisonId, int offset, int limit) {
        List<SemanticQueryContract.GitChangeItem> changes = new ArrayList<>();
        long expectedOrdinal = offset;
        for (Document row : template.getCollection(IndexCollections.GIT_COMPARISON_CHANGES).find(Filters.and(Filters.eq("repoId", repositoryId.value()),
                Filters.eq("comparisonId", comparisonId))).sort(Sorts.ascending("ordinal")).skip(offset).limit(limit)
                .maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
            SemanticQueryContract.GitChangeItem change = change(row);
            if (requiredLong(row, "ordinal") != expectedOrdinal || !repositoryId.value().equals(requiredText(row, "repoId"))
                    || !comparisonId.equals(requiredText(row, "comparisonId"))) {
                throw new IndexContractMismatchException();
            }
            changes.add(change);
            expectedOrdinal++;
        }
        return List.copyOf(changes);
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
                || !validPatchBoundaries(repositoryId, comparisonId, changeId, expectedChunks)) {
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

    private long comparisonCount(RepositoryId repositoryId, String comparisonId) {
        return template.getCollection(IndexCollections.GIT_COMPARISON_CHANGES).countDocuments(Filters.and(Filters.eq("repoId", repositoryId.value()),
                Filters.eq("comparisonId", comparisonId)), new CountOptions().maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS));
    }

    private long changeCount(RepositoryId repositoryId, String comparisonId, String changeId) {
        return template.getCollection(IndexCollections.GIT_COMPARISON_CHANGES).countDocuments(Filters.and(Filters.eq("repoId", repositoryId.value()),
                Filters.eq("comparisonId", comparisonId), Filters.eq("changeId", changeId)), new CountOptions().maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS));
    }

    private Document comparisonBoundary(RepositoryId repositoryId, String comparisonId, org.bson.conversions.Bson sort) {
        Document boundary = template.getCollection(IndexCollections.GIT_COMPARISON_CHANGES).find(Filters.and(Filters.eq("repoId", repositoryId.value()),
                Filters.eq("comparisonId", comparisonId))).projection(new Document("ordinal", 1)).sort(sort).limit(1)
                .maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS).first();
        if (Objects.isNull(boundary)) {
            throw new IndexContractMismatchException();
        }
        return boundary;
    }

    private Document patchBoundary(RepositoryId repositoryId, String comparisonId, String changeId, org.bson.conversions.Bson sort) {
        Document boundary = template.getCollection(IndexCollections.GIT_COMPARISON_PATCHES).find(Filters.and(Filters.eq("repoId", repositoryId.value()),
                Filters.eq("comparisonId", comparisonId), Filters.eq("changeId", changeId))).projection(new Document("ordinal", 1)).sort(sort).limit(1)
                .maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS).first();
        if (Objects.isNull(boundary)) {
            throw new IndexContractMismatchException();
        }
        return boundary;
    }

    private boolean validPatchBoundaries(RepositoryId repositoryId, String comparisonId, String changeId, long expectedChunks) {
        Document first = patchBoundary(repositoryId, comparisonId, changeId, Sorts.ascending("ordinal"));
        Document last = patchBoundary(repositoryId, comparisonId, changeId, Sorts.descending("ordinal"));
        return requiredLong(first, "ordinal") == 0L && requiredLong(last, "ordinal") == expectedChunks - 1L;
    }

    private record PatchPage(String text, boolean hasNext) { }

    private static SemanticQueryContract.GitChangeItem change(Document row) {
        String kindText = requiredText(row, "kind");
        GitChangeKind kind = enumValue(GitChangeKind.class, kindText);
        String oldPath = requiredString(row, "oldPath");
        String newPath = requiredString(row, "newPath");
        byte[] oldRawPath = requiredBytes(row, "oldRawPath");
        byte[] newRawPath = requiredBytes(row, "newRawPath");
        String oldMode = requiredString(row, "oldMode");
        String newMode = requiredString(row, "newMode");
        String oldBlobId = requiredString(row, "oldBlobId");
        String newBlobId = requiredString(row, "newBlobId");
        String status = requiredText(row, "diffStatus");
        validateStatus(status);
        validateEndpoint(oldPath, oldRawPath, requiredString(row, "oldPathKey"), oldMode, oldBlobId);
        validateEndpoint(newPath, newRawPath, requiredString(row, "newPathKey"), newMode, newBlobId);
        validateChangeCombination(kind, oldPath, oldRawPath, oldMode, oldBlobId, newPath, newRawPath, newMode, newBlobId);
        validatePatchMetadata(status, requiredLong(row, "patchChunkCount"));
        return new SemanticQueryContract.GitChangeItem(requiredText(row, "changeId"), kind.name(), oldPath, newPath, oldMode, newMode, oldBlobId, newBlobId, status);
    }

    private static void validateStatus(String status) {
        if ("AVAILABLE".equals(status)) {
            return;
        }
        GitFileContentStatus contentStatus = enumValue(GitFileContentStatus.class, status);
        if (contentStatus == GitFileContentStatus.TEXT) {
            throw new IndexContractMismatchException();
        }
    }

    private static void validatePatchMetadata(String status, long patchChunkCount) {
        if (("AVAILABLE".equals(status) && patchChunkCount == 0L) || (!"AVAILABLE".equals(status) && patchChunkCount != 0L)) {
            throw new IndexContractMismatchException();
        }
    }

    private static void validateEndpoint(String path, byte[] rawPath, String pathKey, String mode, String blobId) {
        if (path.isEmpty()) {
            if (rawPath.length != 0 || !pathKey.isEmpty() || !mode.isEmpty() || !blobId.isEmpty()) {
                throw new IndexContractMismatchException();
            }
            return;
        }
        if (rawPath.length == 0 || !pathKey.equals(java.util.HexFormat.of().formatHex(rawPath)) || !path.equals(displayPath(rawPath))
                || !mode.matches("[0-7]{6}") || !blobId.matches("[0-9a-f]{40}")) {
            throw new IndexContractMismatchException();
        }
    }

    private static void validateChangeCombination(GitChangeKind kind, String oldPath, byte[] oldRawPath, String oldMode, String oldBlobId,
                                                  String newPath, byte[] newRawPath, String newMode, String newBlobId) {
        boolean oldPresent = !oldPath.isEmpty();
        boolean newPresent = !newPath.isEmpty();
        if ((kind == GitChangeKind.ADD && (oldPresent || oldRawPath.length != 0 || !oldMode.isEmpty() || !oldBlobId.isEmpty() || !newPresent))
                || (kind == GitChangeKind.DELETE && (!oldPresent || newPresent || newRawPath.length != 0 || !newMode.isEmpty() || !newBlobId.isEmpty()))
                || ((kind == GitChangeKind.MODIFY || kind == GitChangeKind.MODE || kind == GitChangeKind.RENAME) && (!oldPresent || !newPresent))) {
            throw new IndexContractMismatchException();
        }
    }

    private static String displayPath(byte[] rawPath) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(rawPath)).toString();
        } catch (CharacterCodingException exception) {
            return "\u0000raw-path-hex:" + java.util.HexFormat.of().formatHex(rawPath);
        }
    }

    private static byte[] requiredBytes(Document document, String field) {
        Object value = document.get(field);
        if (value instanceof Binary binary) {
            return binary.getData();
        }
        if (value instanceof byte[] bytes) {
            return Arrays.copyOf(bytes, bytes.length);
        }
        throw new IndexContractMismatchException();
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException exception) {
            throw new IndexContractMismatchException();
        }
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
