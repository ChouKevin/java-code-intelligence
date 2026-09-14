package com.java.semantic.indexer.store;

import com.java.semantic.indexer.job.IndexJob;
import com.java.semantic.repository.config.RepositoryProperties;
import com.java.semantic.model.git.GitBranch;
import com.java.semantic.model.git.GitCatalogManifest;
import com.java.semantic.model.git.GitCommit;
import com.java.semantic.model.git.GitEvidenceId;
import com.java.semantic.model.git.GitEvidenceState;
import com.java.semantic.model.git.GitHistoryManifest;
import com.java.semantic.model.git.GitPreparedComparison;
import com.java.semantic.model.git.GitSnapshotEntry;
import com.java.semantic.model.git.GitSnapshotId;
import com.java.semantic.model.git.GitComparisonId;
import com.java.semantic.model.git.GitComparisonChange;
import com.java.semantic.model.index.IndexCollections;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.bson.types.Binary;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Writes immutable Git rows first and makes them visible only through a final READY manifest transition. */
@Component
public final class GitEvidencePublicationStore {
    private static final int CHUNK_BYTES = 64 * 1024;
    private final MongoTemplate template;
    private final RepositoryProperties properties;

    public GitEvidencePublicationStore(MongoTemplate template) {
        this(template, new RepositoryProperties());
    }

    @Autowired
    public GitEvidencePublicationStore(MongoTemplate template, RepositoryProperties properties) {
        this.template = Objects.requireNonNull(template, "mongo template is required");
        this.properties = Objects.requireNonNull(properties, "repository properties are required");
    }

    public GitCatalogManifest beginCatalog(IndexJob job, Instant observedAt) {
        verifySchemaBeforeEvidence();
        GitEvidenceId id = GitEvidenceId.create();
        GitCatalogManifest manifest = new GitCatalogManifest(id, job.repositoryId(), observedAt, GitEvidenceState.PREPARING, GitCatalogManifest.VERSION);
        template.getCollection(IndexCollections.GIT_EVIDENCE_MANIFESTS).insertOne(new Document("repoId", job.repositoryId().value())
                .append("evidenceId", id.value()).append("kind", "CATALOG").append("state", "PREPARING")
                .append("gitEvidenceVersion", GitCatalogManifest.VERSION).append("observedAt", java.util.Date.from(observedAt))
                .append("ownerJobId", job.id().value()).append("contentDigest", emptyDigest()).append("total", 0L));
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
                .append("revision", revision.value()).append("preparedAt", java.util.Date.from(preparedAt)).append("ownerJobId", job.id().value())
                .append("contentDigest", emptyDigest()).append("total", 0L));
        bind(job, id);
        return manifest;
    }

    public void appendBranches(GitCatalogManifest manifest, List<GitBranch> branches) {
        long ordinal = 0L;
        String digest = emptyDigest();
        for (GitBranch branch : branches) {
            template.getCollection(IndexCollections.GIT_BRANCHES).insertOne(new Document("repoId", manifest.repositoryId().value())
                    .append("catalogId", manifest.catalogId().value()).append("ordinal", ordinal).append("branch", branch.name())
                    .append("head", branch.head().value()));
            digest = digest(digest, catalogRow(ordinal, branch.name(), branch.head().value()));
            ordinal++;
        }
        setContentDigest(manifest.repositoryId(), manifest.catalogId(), digest);
        readyCatalog(manifest, branches.size());
    }

    /** Seals a catalog after its streamed rows have been durably written and validated. */
    public void readyCatalog(GitCatalogManifest manifest, long total) {
        ready(manifest.repositoryId(), manifest.catalogId(), total);
    }

    public void appendCommit(GitHistoryManifest manifest, long ordinal, GitCommit commit) {
        List<String> parents = commit.parents().stream().map(RepositoryRevision::value).toList();
        template.getCollection(IndexCollections.GIT_COMMITS).insertOne(new Document("repoId", manifest.repositoryId().value())
                .append("historyId", manifest.historyId().value()).append("ordinal", ordinal).append("revision", commit.revision().value())
                .append("parents", parents).append("subject", commit.subject()).append("committedAt", java.util.Date.from(commit.committedAt())));
        Document preparing = preparingManifest(manifest.repositoryId(), manifest.historyId());
        setContentDigest(manifest.repositoryId(), manifest.historyId(), digest(preparing.getString("contentDigest"),
                historyRow(ordinal, commit.revision().value(), parents, commit.subject(), commit.committedAt().toEpochMilli())));
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

    /** Publishes full immutable snapshots first, then makes their direct comparison visible last. */
    public void publishComparison(IndexJob job, GitPreparedComparison comparison, Instant preparedAt) {
        verifySchemaBeforeEvidence();
        GitComparisonId comparisonId = GitComparisonId.create();
        GitSnapshotId previousSnapshot = GitSnapshotId.create();
        GitSnapshotId currentSnapshot = GitSnapshotId.create();
        template.getCollection(IndexCollections.GIT_EVIDENCE_MANIFESTS).insertOne(new Document("repoId", job.repositoryId().value())
                .append("evidenceId", comparisonId.value()).append("kind", "COMPARISON").append("state", "PREPARING").append("gitEvidenceVersion", 1)
                .append("previous", comparison.previous().value()).append("current", comparison.current().value()).append("previousSnapshotId", previousSnapshot.value())
                .append("currentSnapshotId", currentSnapshot.value()).append("ancestry", comparison.ancestry().name()).append("preparedAt", java.util.Date.from(preparedAt))
                .append("ownerJobId", job.id().value()).append("total", (long) comparison.changes().size()));
        bindComparison(job, comparisonId, previousSnapshot, currentSnapshot);
        publishSnapshot(job, previousSnapshot, comparison.previous().value(), comparison.previousEntries(), preparedAt);
        publishSnapshot(job, currentSnapshot, comparison.current().value(), comparison.currentEntries(), preparedAt);
        long ordinal = 0L;
        for (GitComparisonChange change : comparison.changes()) {
            template.getCollection(IndexCollections.GIT_COMPARISON_CHANGES).insertOne(new Document("repoId", job.repositoryId().value())
                    .append("comparisonId", comparisonId.value()).append("ordinal", ordinal).append("changeId", change.changeId()).append("kind", change.kind().name())
                    .append("oldPath", change.oldPath()).append("newPath", change.newPath()).append("oldRawPath", change.oldRawPath())
                    .append("newRawPath", change.newRawPath()).append("oldPathKey", pathKey(change.oldRawPath())).append("newPathKey", pathKey(change.newRawPath()))
                    .append("oldMode", change.oldMode()).append("newMode", change.newMode())
                    .append("oldBlobId", change.oldBlobId()).append("newBlobId", change.newBlobId()).append("diffStatus", change.diffStatus())
                    .append("patchChunkCount", (long) change.patchChunks().size()));
            appendPatchChunks(job.repositoryId(), comparisonId, change);
            ordinal++;
        }
        validateComparisonPublication(job.repositoryId(), comparisonId, previousSnapshot, currentSnapshot, comparison);
        markReady(job.repositoryId(), new GitEvidenceId(comparisonId.value()));
    }

    private void publishSnapshot(IndexJob job, GitSnapshotId snapshotId, String revision, List<GitSnapshotEntry> entries, Instant preparedAt) {
        EvidenceLimits limits = evidenceLimits();
        long totalText = 0L;
        long textEntries = 0L;
        template.getCollection(IndexCollections.GIT_EVIDENCE_MANIFESTS).insertOne(new Document("repoId", job.repositoryId().value()).append("evidenceId", snapshotId.value())
                .append("kind", "SNAPSHOT").append("state", "PREPARING").append("gitEvidenceVersion", 1).append("revision", revision)
                .append("preparedAt", java.util.Date.from(preparedAt)).append("ownerJobId", job.id().value()).append("total", (long) entries.size())
                .append("contentDigest", emptyDigest()).append("fileTextBytesLimit", limits.fileTextBytes())
                .append("snapshotTextBytesLimit", limits.snapshotTextBytes()).append("contentCoverage", new Document("textBytes", 0L)
                        .append("textEntries", 0L).append("entryCount", (long) entries.size())));
        long ordinal = 0L;
        String digest = emptyDigest();
        for (GitSnapshotEntry entry : entries) {
            byte[] bytes = entry.bytes();
            boolean text = entry.contentStatus().name().equals("TEXT");
            if (text) {
                totalText += entry.byteLength();
                textEntries++;
            }
            if (totalText > limits.snapshotTextBytes() || text && entry.byteLength() > limits.fileTextBytes()) {
                throw new PublicationConflictException();
            }
            byte[] persistedBytes = text ? bytes : new byte[0];
            String checksum = checksum(persistedBytes);
            String pathKey = pathKey(entry.rawPath());
            List<SnapshotChunk> chunks = snapshotChunks(persistedBytes);
            template.getCollection(IndexCollections.GIT_SNAPSHOT_FILES).insertOne(new Document("repoId", job.repositoryId().value()).append("snapshotId", snapshotId.value())
                    .append("ordinal", ordinal).append("path", entry.path()).append("rawPath", entry.rawPath()).append("pathKey", pathKey)
                    .append("mode", entry.mode()).append("blobId", entry.blobId()).append("contentStatus", entry.contentStatus().name())
                    .append("byteLength", entry.byteLength()).append("checksum", checksum).append("chunkCount", (long) chunks.size()));
            for (SnapshotChunk chunk : chunks) {
                template.getCollection(IndexCollections.GIT_SNAPSHOT_CHUNKS).insertOne(new Document("repoId", job.repositoryId().value()).append("snapshotId", snapshotId.value())
                        .append("pathKey", pathKey).append("ordinal", chunk.ordinal()).append("byteOffset", chunk.byteOffset())
                        .append("line", chunk.line()).append("column", chunk.column()).append("bytes", chunk.bytes()));
            }
            digest = digest(digest, snapshotRow(ordinal, entry, checksum));
            ordinal++;
        }
        setContentDigest(job.repositoryId(), new GitEvidenceId(snapshotId.value()), digest);
        setSnapshotCoverage(job.repositoryId(), snapshotId, totalText, textEntries, entries.size());
        validateSnapshotPublication(job.repositoryId(), snapshotId, revision, entries, totalText, limits);
        markReady(job.repositoryId(), new GitEvidenceId(snapshotId.value()));
    }

    void validateComparisonPublication(RepositoryId repositoryId, GitComparisonId comparisonId, GitSnapshotId previousSnapshot,
                                       GitSnapshotId currentSnapshot, GitPreparedComparison expected) {
        long readySnapshots = template.getCollection(IndexCollections.GIT_EVIDENCE_MANIFESTS).countDocuments(Filters.and(
                Filters.eq("repoId", repositoryId.value()), Filters.eq("kind", "SNAPSHOT"), Filters.eq("state", "READY"),
                Filters.in("evidenceId", List.of(previousSnapshot.value(), currentSnapshot.value()))));
        Document manifest = template.getCollection(IndexCollections.GIT_EVIDENCE_MANIFESTS).find(Filters.and(Filters.eq("repoId", repositoryId.value()),
                Filters.eq("evidenceId", comparisonId.value()), Filters.eq("kind", "COMPARISON"), Filters.eq("state", "PREPARING"))).first();
        if (readySnapshots != 2L || Objects.isNull(manifest) || !expected.previous().value().equals(manifest.getString("previous"))
                || !expected.current().value().equals(manifest.getString("current")) || !previousSnapshot.value().equals(manifest.getString("previousSnapshotId"))
                || !currentSnapshot.value().equals(manifest.getString("currentSnapshotId")) || !expected.ancestry().name().equals(manifest.getString("ancestry"))) {
            throw new PublicationConflictException();
        }
        validateReadySnapshot(repositoryId, previousSnapshot, expected.previous().value(), expected.previousEntries());
        validateReadySnapshot(repositoryId, currentSnapshot, expected.current().value(), expected.currentEntries());
        validateChanges(repositoryId, comparisonId, expected.changes());
    }

    private void appendPatchChunks(RepositoryId repositoryId, GitComparisonId comparisonId, GitComparisonChange change) {
        if (!"AVAILABLE".equals(change.diffStatus())) {
            return;
        }
        long ordinal = 0L;
        for (String patch : change.patchChunks()) {
            byte[] patchBytes = patch.getBytes(StandardCharsets.UTF_8);
            if (patchBytes.length == 0 || patchBytes.length > CHUNK_BYTES) {
                throw new PublicationConflictException();
            }
            template.getCollection(IndexCollections.GIT_COMPARISON_PATCHES).insertOne(new Document("repoId", repositoryId.value())
                    .append("comparisonId", comparisonId.value()).append("changeId", change.changeId()).append("ordinal", ordinal)
                    .append("patch", patch));
            ordinal++;
        }
    }

    private void validateSnapshotPublication(RepositoryId repositoryId, GitSnapshotId snapshotId, String revision,
                                             List<GitSnapshotEntry> expectedEntries, long expectedTextBytes, EvidenceLimits limits) {
        validateSnapshotContents(repositoryId, snapshotId, revision, expectedEntries, expectedTextBytes, "PREPARING", limits);
    }

    private void validateReadySnapshot(RepositoryId repositoryId, GitSnapshotId snapshotId, String revision,
                                       List<GitSnapshotEntry> expectedEntries) {
        long expectedTextBytes = expectedEntries.stream().filter(entry -> entry.contentStatus().name().equals("TEXT"))
                .mapToLong(GitSnapshotEntry::byteLength).sum();
        Document manifest = snapshotManifest(repositoryId, snapshotId, "READY");
        validateSnapshotContents(repositoryId, snapshotId, revision, expectedEntries, expectedTextBytes, "READY", limitsFrom(manifest));
    }

    private void validateSnapshotContents(RepositoryId repositoryId, GitSnapshotId snapshotId, String revision,
                                          List<GitSnapshotEntry> expectedEntries, long expectedTextBytes, String state, EvidenceLimits limits) {
        Document manifest = snapshotManifest(repositoryId, snapshotId, state);
        if (Objects.isNull(manifest) || !revision.equals(manifest.getString("revision")) || manifest.getLong("total") != expectedEntries.size()) {
            throw new PublicationConflictException();
        }
        long actualTextBytes = 0L;
        long actualTextEntries = 0L;
        String digest = emptyDigest();
        long ordinal = 0L;
        for (GitSnapshotEntry expected : expectedEntries) {
            Document file = template.getCollection(IndexCollections.GIT_SNAPSHOT_FILES).find(Filters.and(Filters.eq("repoId", repositoryId.value()),
                    Filters.eq("snapshotId", snapshotId.value()), Filters.eq("ordinal", ordinal))).first();
            Optional<byte[]> storedRawPath = Objects.nonNull(file) ? binaryBytes(file.get("rawPath")) : Optional.empty();
            if (Objects.isNull(file) || !expected.path().equals(file.getString("path")) || storedRawPath.isEmpty()
                    || !Arrays.equals(expected.rawPath(), storedRawPath.get()) || !pathKey(expected.rawPath()).equals(file.getString("pathKey"))
                    || !expected.mode().equals(file.getString("mode"))
                    || !expected.blobId().equals(file.getString("blobId")) || !expected.contentStatus().name().equals(file.getString("contentStatus"))
                    || file.getLong("byteLength") != expected.byteLength()) {
                throw new PublicationConflictException();
            }
            byte[] expectedBytes = expected.contentStatus().name().equals("TEXT") ? expected.bytes() : new byte[0];
            String checksum = checksum(expectedBytes);
            Number chunkCount = file.get("chunkCount", Number.class);
            if (!checksum.equals(file.getString("checksum")) || Objects.isNull(chunkCount)
                    || !validSnapshotChunks(repositoryId, snapshotId, expected.rawPath(), expectedBytes, chunkCount.longValue())) {
                throw new PublicationConflictException();
            }
            if (expected.contentStatus().name().equals("TEXT")) {
                actualTextBytes += expected.byteLength();
                actualTextEntries++;
            }
            digest = digest(digest, snapshotRow(ordinal, expected, checksum));
            ordinal++;
        }
        long actualFiles = template.getCollection(IndexCollections.GIT_SNAPSHOT_FILES).countDocuments(Filters.and(Filters.eq("repoId", repositoryId.value()),
                Filters.eq("snapshotId", snapshotId.value())));
        if (actualFiles != expectedEntries.size() || actualTextBytes != expectedTextBytes || !digest.equals(manifest.getString("contentDigest"))
                || !coverageMatches(manifest, actualTextBytes, actualTextEntries, expectedEntries.size())
                || !limitsMatch(manifest, limits)) {
            throw new PublicationConflictException();
        }
    }

    private boolean validSnapshotChunks(RepositoryId repositoryId, GitSnapshotId snapshotId, byte[] rawPath, byte[] expectedBytes, long expectedChunkCount) {
        if (expectedChunkCount < 0L) {
            return false;
        }
        List<Document> chunks = new ArrayList<>();
        template.getCollection(IndexCollections.GIT_SNAPSHOT_CHUNKS).find(Filters.and(Filters.eq("repoId", repositoryId.value()),
                Filters.eq("snapshotId", snapshotId.value()), Filters.eq("pathKey", pathKey(rawPath)))).sort(com.mongodb.client.model.Sorts.ascending("ordinal")).into(chunks);
        byte[] actual = new byte[0];
        long ordinal = 0L;
        long byteOffset = 0L;
        SourcePosition position = SourcePosition.initial();
        for (Document chunk : chunks) {
            Number storedOrdinal = chunk.get("ordinal", Number.class);
            Number storedByteOffset = chunk.get("byteOffset", Number.class);
            Number storedLine = chunk.get("line", Number.class);
            Number storedColumn = chunk.get("column", Number.class);
            Optional<byte[]> bytes = binaryBytes(chunk.get("bytes"));
            if (Objects.isNull(storedOrdinal) || Objects.isNull(storedByteOffset) || Objects.isNull(storedLine) || Objects.isNull(storedColumn)
                    || storedOrdinal.longValue() != ordinal || storedByteOffset.longValue() != byteOffset || storedLine.longValue() != position.line()
                    || storedColumn.longValue() != position.column() || bytes.isEmpty() || bytes.get().length == 0 || bytes.get().length > CHUNK_BYTES) {
                return false;
            }
            Optional<SourcePosition> next = advance(position, bytes.get());
            if (next.isEmpty()) {
                return false;
            }
            byte[] joined = Arrays.copyOf(actual, actual.length + bytes.get().length);
            System.arraycopy(bytes.get(), 0, joined, actual.length, bytes.get().length);
            actual = joined;
            byteOffset += bytes.get().length;
            position = next.get();
            ordinal++;
        }
        return ordinal == expectedChunkCount && Arrays.equals(expectedBytes, actual);
    }

    private static List<SnapshotChunk> snapshotChunks(byte[] bytes) {
        if (bytes.length == 0) {
            return List.of();
        }
        List<SnapshotChunk> chunks = new ArrayList<>();
        SourcePosition position = SourcePosition.initial();
        for (int start = 0, ordinal = 0; start < bytes.length; ordinal++) {
            int end = snapshotUtf8ChunkEnd(bytes, start);
            byte[] chunkBytes = Arrays.copyOfRange(bytes, start, end);
            chunks.add(new SnapshotChunk(ordinal, start, position.line(), position.column(), chunkBytes));
            Optional<SourcePosition> next = advance(position, chunkBytes);
            if (next.isEmpty()) {
                throw new PublicationConflictException();
            }
            position = next.get();
            start = end;
        }
        return List.copyOf(chunks);
    }

    private static int snapshotUtf8ChunkEnd(byte[] bytes, int start) {
        int end = Math.min(start + CHUNK_BYTES, bytes.length);
        while (end < bytes.length && (bytes[end] & 0xC0) == 0x80) {
            end--;
        }
        if (end == start) {
            throw new PublicationConflictException();
        }
        return end;
    }

    private static Optional<SourcePosition> advance(SourcePosition initial, byte[] bytes) {
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            long line = initial.line();
            long column = initial.column();
            for (int character = 0; character < text.length();) {
                int codePoint = text.codePointAt(character);
                character += Character.charCount(codePoint);
                if (codePoint == '\n') {
                    line++;
                    column = 1L;
                } else {
                    column++;
                }
            }
            return Optional.of(new SourcePosition(line, column));
        } catch (CharacterCodingException exception) {
            return Optional.empty();
        }
    }

    private record SnapshotChunk(long ordinal, long byteOffset, long line, long column, byte[] bytes) { }
    private record SourcePosition(long line, long column) {
        private static SourcePosition initial() { return new SourcePosition(1L, 1L); }
    }

    private void validateChanges(RepositoryId repositoryId, GitComparisonId comparisonId, List<GitComparisonChange> expectedChanges) {
        long ordinal = 0L;
        for (GitComparisonChange expected : expectedChanges) {
            Document change = template.getCollection(IndexCollections.GIT_COMPARISON_CHANGES).find(Filters.and(Filters.eq("repoId", repositoryId.value()),
                    Filters.eq("comparisonId", comparisonId.value()), Filters.eq("ordinal", ordinal))).first();
            if (Objects.isNull(change) || !expected.changeId().equals(change.getString("changeId")) || !expected.kind().name().equals(change.getString("kind"))
                    || !expected.oldPath().equals(change.getString("oldPath")) || !expected.newPath().equals(change.getString("newPath"))
                    || binaryBytes(change.get("oldRawPath")).filter(bytes -> Arrays.equals(bytes, expected.oldRawPath())).isEmpty()
                    || binaryBytes(change.get("newRawPath")).filter(bytes -> Arrays.equals(bytes, expected.newRawPath())).isEmpty()
                    || !pathKey(expected.oldRawPath()).equals(change.getString("oldPathKey")) || !pathKey(expected.newRawPath()).equals(change.getString("newPathKey"))
                    || !expected.oldMode().equals(change.getString("oldMode")) || !expected.newMode().equals(change.getString("newMode"))
                    || !expected.oldBlobId().equals(change.getString("oldBlobId")) || !expected.newBlobId().equals(change.getString("newBlobId"))
                    || !expected.diffStatus().equals(change.getString("diffStatus")) || !numberEquals(change, "patchChunkCount", expected.patchChunks().size())
                    || !validPatchChunks(repositoryId, comparisonId, expected)) {
                throw new PublicationConflictException();
            }
            ordinal++;
        }
        long actual = template.getCollection(IndexCollections.GIT_COMPARISON_CHANGES).countDocuments(Filters.and(Filters.eq("repoId", repositoryId.value()),
                Filters.eq("comparisonId", comparisonId.value())));
        if (actual != expectedChanges.size()) {
            throw new PublicationConflictException();
        }
    }

    private boolean validPatchChunks(RepositoryId repositoryId, GitComparisonId comparisonId, GitComparisonChange expected) {
        List<Document> chunks = new ArrayList<>();
        template.getCollection(IndexCollections.GIT_COMPARISON_PATCHES).find(Filters.and(Filters.eq("repoId", repositoryId.value()),
                Filters.eq("comparisonId", comparisonId.value()), Filters.eq("changeId", expected.changeId())))
                .sort(com.mongodb.client.model.Sorts.ascending("ordinal")).into(chunks);
        if (!"AVAILABLE".equals(expected.diffStatus())) {
            return chunks.isEmpty() && expected.patch().isEmpty();
        }
        long ordinal = 0L;
        for (Document chunk : chunks) {
            Number storedOrdinal = chunk.get("ordinal", Number.class);
            String patch = chunk.getString("patch");
            if (Objects.isNull(storedOrdinal) || storedOrdinal.longValue() != ordinal || Objects.isNull(patch)
                    || patch.getBytes(StandardCharsets.UTF_8).length == 0 || patch.getBytes(StandardCharsets.UTF_8).length > CHUNK_BYTES
                    || ordinal >= expected.patchChunks().size() || !patch.equals(expected.patchChunks().get((int) ordinal))) {
                return false;
            }
            ordinal++;
        }
        return ordinal == expected.patchChunks().size();
    }

    private EvidenceLimits evidenceLimits() {
        return new EvidenceLimits(properties.getGitEvidenceFileTextBytes(), properties.getGitEvidenceSnapshotTextBytes());
    }

    private Document snapshotManifest(RepositoryId repositoryId, GitSnapshotId snapshotId, String state) {
        return template.getCollection(IndexCollections.GIT_EVIDENCE_MANIFESTS).find(Filters.and(Filters.eq("repoId", repositoryId.value()),
                Filters.eq("evidenceId", snapshotId.value()), Filters.eq("kind", "SNAPSHOT"), Filters.eq("state", state))).first();
    }

    private static EvidenceLimits limitsFrom(Document manifest) {
        if (Objects.isNull(manifest)) {
            throw new PublicationConflictException();
        }
        Number fileLimit = manifest.get("fileTextBytesLimit", Number.class);
        Number snapshotLimit = manifest.get("snapshotTextBytesLimit", Number.class);
        if (Objects.isNull(fileLimit) || Objects.isNull(snapshotLimit)) {
            throw new PublicationConflictException();
        }
        return new EvidenceLimits(fileLimit.longValue(), snapshotLimit.longValue());
    }

    private void setSnapshotCoverage(RepositoryId repositoryId, GitSnapshotId snapshotId, long textBytes, long textEntries, int entryCount) {
        long modified = template.getCollection(IndexCollections.GIT_EVIDENCE_MANIFESTS).updateOne(Filters.and(Filters.eq("repoId", repositoryId.value()),
                Filters.eq("evidenceId", snapshotId.value()), Filters.eq("state", "PREPARING")), Updates.set("contentCoverage",
                new Document("textBytes", textBytes).append("textEntries", textEntries).append("entryCount", (long) entryCount))).getModifiedCount();
        if (modified != 1L) {
            throw new PublicationConflictException();
        }
    }

    private static boolean coverageMatches(Document manifest, long textBytes, long textEntries, int entryCount) {
        Document coverage = manifest.get("contentCoverage", Document.class);
        if (Objects.isNull(coverage)) {
            return false;
        }
        Number storedBytes = coverage.get("textBytes", Number.class);
        Number storedEntries = coverage.get("textEntries", Number.class);
        Number storedTotal = coverage.get("entryCount", Number.class);
        return Objects.nonNull(storedBytes) && Objects.nonNull(storedEntries) && Objects.nonNull(storedTotal)
                && storedBytes.longValue() == textBytes && storedEntries.longValue() == textEntries && storedTotal.longValue() == entryCount;
    }

    private static boolean limitsMatch(Document manifest, EvidenceLimits limits) {
        Number fileLimit = manifest.get("fileTextBytesLimit", Number.class);
        Number snapshotLimit = manifest.get("snapshotTextBytesLimit", Number.class);
        return Objects.nonNull(fileLimit) && Objects.nonNull(snapshotLimit) && fileLimit.longValue() == limits.fileTextBytes()
                && snapshotLimit.longValue() == limits.snapshotTextBytes();
    }

    private static boolean numberEquals(Document document, String field, int expected) {
        Number actual = document.get(field, Number.class);
        return Objects.nonNull(actual) && actual.longValue() == expected;
    }

    private static int utf8ChunkEnd(byte[] bytes, int offset) {
        int end = Math.min(bytes.length, offset + CHUNK_BYTES);
        while (end < bytes.length && (bytes[end] & 0xC0) == 0x80) {
            end--;
        }
        if (end == offset) {
            throw new PublicationConflictException();
        }
        return end;
    }

    private static Optional<byte[]> binaryBytes(Object value) {
        if (value instanceof Binary binary) {
            return Optional.of(binary.getData());
        }
        if (value instanceof byte[] bytes) {
            return Optional.of(Arrays.copyOf(bytes, bytes.length));
        }
        return Optional.empty();
    }

    private static String snapshotRow(long ordinal, GitSnapshotEntry entry, String checksum) {
        return ordinal + "\\u0000" + entry.path() + "\\u0000" + pathKey(entry.rawPath()) + "\\u0000" + entry.mode() + "\\u0000" + entry.blobId() + "\\u0000"
                + entry.contentStatus().name() + "\\u0000" + entry.byteLength() + "\\u0000" + checksum;
    }

    private static String pathKey(byte[] rawPath) { return java.util.HexFormat.of().formatHex(rawPath); }

    private static String checksum(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record EvidenceLimits(long fileTextBytes, long snapshotTextBytes) {
        private EvidenceLimits {
            if (fileTextBytes <= 0L || snapshotTextBytes <= 0L || fileTextBytes > snapshotTextBytes) {
                throw new PublicationConflictException();
            }
        }
    }

    private void bind(IndexJob job, GitEvidenceId evidenceId) {
        template.getCollection(IndexCollections.INDEX_JOBS).updateOne(Filters.and(Filters.eq("jobId", job.id().value()),
                Filters.eq("repoId", job.repositoryId().value()), Filters.eq("active", true)), Updates.set("gitEvidence.evidenceId", evidenceId.value()));
    }

    private void bindComparison(IndexJob job, GitComparisonId comparisonId, GitSnapshotId previousSnapshot, GitSnapshotId currentSnapshot) {
        template.getCollection(IndexCollections.INDEX_JOBS).updateOne(Filters.and(Filters.eq("jobId", job.id().value()),
                Filters.eq("repoId", job.repositoryId().value()), Filters.eq("active", true)), Updates.combine(
                Updates.set("gitEvidence.evidenceId", comparisonId.value()), Updates.set("gitEvidence.previousSnapshotId", previousSnapshot.value()),
                Updates.set("gitEvidence.currentSnapshotId", currentSnapshot.value())));
    }

    private void markReady(RepositoryId repositoryId, GitEvidenceId evidenceId) {
        long modified = template.getCollection(IndexCollections.GIT_EVIDENCE_MANIFESTS).updateOne(Filters.and(Filters.eq("repoId", repositoryId.value()),
                Filters.eq("evidenceId", evidenceId.value()), Filters.eq("state", "PREPARING")), Updates.set("state", "READY")).getModifiedCount();
        if (modified != 1L) {
            throw new PublicationConflictException();
        }
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
        if (!"CATALOG".equals(kind) && !"HISTORY".equals(kind)) {
            throw new PublicationConflictException();
        }
        if (!repositoryId.value().equals(manifest.getString("repoId")) || !evidenceId.value().equals(manifest.getString("evidenceId"))
                || Objects.isNull(manifest.getDate("observedAt")) && Objects.isNull(manifest.getDate("preparedAt"))) {
            throw new PublicationConflictException();
        }
        String collection = "CATALOG".equals(kind) ? IndexCollections.GIT_BRANCHES : IndexCollections.GIT_COMMITS;
        String evidenceField = "CATALOG".equals(kind) ? "catalogId" : "historyId";
        long ordinal = 0L;
        String digest = emptyDigest();
        for (Document row : template.getCollection(collection).find(Filters.and(Filters.eq("repoId", repositoryId.value()),
                Filters.eq(evidenceField, evidenceId.value()))).sort(com.mongodb.client.model.Sorts.ascending("ordinal"))) {
            Number storedOrdinal = row.get("ordinal", Number.class);
            if (Objects.isNull(storedOrdinal) || storedOrdinal.longValue() != ordinal) {
                throw new PublicationConflictException();
            }
            validateRow(kind, manifest, row, ordinal);
            digest = "CATALOG".equals(kind)
                    ? digest(digest, catalogRow(ordinal, row.getString("branch"), row.getString("head")))
                    : digest(digest, historyRow(ordinal, row.getString("revision"), row.getList("parents", String.class, List.of()),
                    row.getString("subject"), row.getDate("committedAt").getTime()));
            ordinal++;
        }
        if (ordinal != total) {
            throw new PublicationConflictException();
        }
        if ("HISTORY".equals(kind) && ordinal == 0L) {
            throw new PublicationConflictException();
        }
        if (!digest.equals(manifest.getString("contentDigest"))) {
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
        if (!hasText(manifest.getString("branch")) || !isSha(manifest.getString("revision")) || !hasText(manifest.getString("catalogId"))) {
            throw new PublicationConflictException();
        }
        String revision = row.getString("revision");
        List<String> parents = row.getList("parents", String.class, List.of());
        if (!isSha(revision) || parents.stream().anyMatch(parent -> !isSha(parent)) || !hasString(row, "subject")
                || Objects.isNull(row.getDate("committedAt")) || (ordinal == 0L && !revision.equals(manifest.getString("revision")))) {
            throw new PublicationConflictException();
        }
    }

    private static boolean hasString(Document row, String field) { return row.get(field) instanceof String; }

    private static boolean hasText(String value) {
        return Objects.nonNull(value) && !value.isBlank();
    }

    private static boolean isSha(String value) {
        return Objects.nonNull(value) && value.matches("[0-9a-f]{40}");
    }

    private Document preparingManifest(RepositoryId repositoryId, GitEvidenceId evidenceId) {
        Document manifest = template.getCollection(IndexCollections.GIT_EVIDENCE_MANIFESTS).find(Filters.and(Filters.eq("repoId", repositoryId.value()),
                Filters.eq("evidenceId", evidenceId.value()), Filters.eq("state", "PREPARING"))).first();
        if (Objects.isNull(manifest)) {
            throw new PublicationConflictException();
        }
        return manifest;
    }

    private void setContentDigest(RepositoryId repositoryId, GitEvidenceId evidenceId, String digest) {
        long modified = template.getCollection(IndexCollections.GIT_EVIDENCE_MANIFESTS).updateOne(Filters.and(Filters.eq("repoId", repositoryId.value()),
                Filters.eq("evidenceId", evidenceId.value()), Filters.eq("state", "PREPARING")), Updates.set("contentDigest", digest)).getModifiedCount();
        if (modified != 1L) {
            throw new PublicationConflictException();
        }
    }

    private static String emptyDigest() { return digest("", ""); }

    private static String catalogRow(long ordinal, String branch, String head) { return ordinal + "\\u0000" + branch + "\\u0000" + head; }

    private static String historyRow(long ordinal, String revision, List<String> parents, String subject, long committedAt) {
        return ordinal + "\\u0000" + revision + "\\u0000" + String.join("\\u0001", parents) + "\\u0000" + subject + "\\u0000" + committedAt;
    }

    private static String digest(String previous, String row) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(messageDigest.digest((previous + "\\u0002" + row).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
