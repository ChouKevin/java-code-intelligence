package com.java.semantic.query.application;

import com.java.semantic.model.source.SourceEvidenceDocumentCodec;
import com.java.semantic.model.git.GitEvidenceId;
import com.java.semantic.model.git.GitChangeKind;
import com.java.semantic.model.git.GitEvidenceOwnership;
import com.java.semantic.model.git.GitFileContentStatus;
import com.java.semantic.model.git.GitPublicationScope;
import com.java.semantic.model.git.GitComparisonId;
import com.java.semantic.model.git.GitSnapshotId;
import com.java.semantic.model.index.IndexCollections;
import com.java.semantic.model.index.IndexSchemaContract;
import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.index.ManifestDigest;
import com.java.semantic.model.query.SelectedGeneration;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.model.review.ReviewId;
import com.java.semantic.model.source.SourceEvidencePolicy;
import com.java.semantic.model.source.SourceSnapshotMembership;
import com.java.semantic.model.source.ProjectGuideMembership;
import com.java.semantic.model.review.ReviewManifestDocument;
import com.java.semantic.query.config.ConfiguredReadPolicy;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.mongodb.MongoException;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.Binary;
import org.springframework.dao.DataAccessException;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** Mongo-only reader for immutable Git catalog and history manifests. */
public final class GitEvidenceReadService {
    private static final int MAX_CURSOR_CHARACTERS = 2048;
    private static final String DIFF_CURSOR_OPERATION = "git-file-diff";
    private static final String FILE_CURSOR_OPERATION = "git-file";
    private static final String SEARCH_CURSOR_OPERATION = "git-search";
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;
    private static final int MAX_SEARCH_BYTES = 4 * 1024 * 1024;
    private final MongoTemplate template;
    private final ConfiguredReadPolicy readPolicy;
    private final Duration storageTimeout;
    private final ReviewManifestReadService reviews;
    private final SelectedGenerationGuard generations;

    public GitEvidenceReadService(MongoTemplate template, ConfiguredReadPolicy readPolicy, Duration storageTimeout) {
        this(template, readPolicy, storageTimeout, new ReviewManifestReadService(template, readPolicy, storageTimeout));
    }

    public GitEvidenceReadService(MongoTemplate template, ConfiguredReadPolicy readPolicy, Duration storageTimeout,
                                  ReviewManifestReadService reviews) {
        this.template = Objects.requireNonNull(template, "mongo template is required");
        this.readPolicy = Objects.requireNonNull(readPolicy, "read policy is required");
        this.storageTimeout = Objects.requireNonNull(storageTimeout, "storage timeout is required");
        this.reviews = Objects.requireNonNull(reviews, "review manifest reader is required");
        this.generations = new SelectedGenerationGuard(template, readPolicy, storageTimeout);
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
            if (!Objects.equals(required.previous(), manifest.getString("previous")) || !required.current().equals(requiredText(manifest, "current"))) {
                throw new IllegalArgumentException("comparison endpoints do not match the requested revisions");
            }
            long total = comparisonTotal(manifest);
            List<SemanticQueryContract.GitChangeItem> changes = comparisonPage(repositoryId, manifest, required.comparisonId(), required.offset(), required.limit(), total);
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
            if (!Objects.equals(required.previous(), manifest.getString("previous")) || !required.current().equals(requiredText(manifest, "current"))) {
                throw new IllegalArgumentException("comparison endpoints do not match the requested revisions");
            }
            long total = comparisonTotal(manifest);
            Document row = template.getCollection(IndexCollections.GIT_COMPARISON_CHANGES).find(Filters.and(Filters.eq("repoId", repositoryId.value()),
                    Filters.eq("comparisonId", required.comparisonId()), Filters.eq("changeId", required.changeId()))).maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS).first();
            if (Objects.isNull(row)) {
                throw new IndexContractMismatchException();
            }
            SemanticQueryContract.GitChangeItem change = change(row);
            requireAllowedChange(repositoryId, manifest, change);
            if (!required.changeId().equals(change.changeId()) || requiredLong(row, "ordinal") >= total) {
                throw new IndexContractMismatchException();
            }
            long ordinal = required.cursor().map(cursor -> decodeCursor(cursor, repositoryId, required.comparisonId(), required.previous(), required.current(),
                    required.changeId())).orElse(0L);
            PatchPage patchPage = patchPage(repositoryId, required.comparisonId(), required.changeId(), row, ordinal, required.cursor().isPresent());
            return new SemanticQueryContract.GitFileDiffResult(repositoryId.value(), required.comparisonId(), required.previous(), required.current(),
                    change, patchPage.text(), patchPage.hasNext() ? Optional.of(encodeCursor(repositoryId, required.comparisonId(), required.previous(),
                            required.current(), required.changeId(), ordinal + 1L)) : Optional.empty());
        } catch (MongoException | DataAccessException exception) {
            throw new SemanticIndexUnavailableException(exception);
        } catch (RepositoryNotFoundException | GitEvidenceNotFoundException | GitEvidenceNotReadyException | IndexContractMismatchException | IllegalArgumentException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IndexContractMismatchException();
        }
    }

    public SemanticQueryContract.GitFileCollection listFiles(SemanticQueryContract.GitFileListRequest request) {
        SemanticQueryContract.GitFileListRequest required = Objects.requireNonNull(request, "git file list request is required");
        RepositoryId repositoryId = new RepositoryId(required.repositoryId());
        try {
            authorize(repositoryId);
            Document manifest = snapshotManifest(repositoryId, required.snapshotId(), required.revision());
            FileEntries entries = directEntries(repositoryId, required.snapshotId(), required.directory(), required.offset(), required.limit(), manifest);
            return new SemanticQueryContract.GitFileCollection(repositoryId.value(), required.snapshotId(), required.revision(), entries.items(),
                    new SemanticQueryContract.Page(required.offset(), required.limit(), entries.items().size(), entries.total(), entries.hasMore()),
                    coverage(repositoryId, required.snapshotId(), required.directory(), manifest));
        } catch (MongoException | DataAccessException exception) {
            throw new SemanticIndexUnavailableException(exception);
        } catch (RepositoryNotFoundException | GitEvidenceNotFoundException | GitEvidenceNotReadyException | IndexContractMismatchException | IllegalArgumentException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IndexContractMismatchException();
        }
    }

    public SemanticQueryContract.GitFileContent readFile(SemanticQueryContract.GitFileReadRequest request) {
        SemanticQueryContract.GitFileReadRequest required = Objects.requireNonNull(request, "git file read request is required");
        RepositoryId repositoryId = new RepositoryId(required.repositoryId());
        try {
            authorize(repositoryId);
            Document manifest = snapshotManifest(repositoryId, required.snapshotId(), required.revision());
            SnapshotFile file = file(repositoryId, required.snapshotId(), required.path(), manifest);
            if (!"TEXT".equals(file.contentStatus())) {
                return new SemanticQueryContract.GitFileContent(repositoryId.value(), required.snapshotId(), required.revision(), file.path(), file.pathKey(),
                        file.contentStatus(), "", 0, 0, true, true, Optional.empty());
            }
            ReadPosition start = required.cursor().map(cursor -> decodeReadCursor(cursor, repositoryId, required, file))
                    .orElseGet(() -> positionForLine(repositoryId, required.snapshotId(), file, required.startLine().orElse(1)));
            ReadPage page = readPage(repositoryId, required.snapshotId(), file, start, required.maxLines());
            Optional<String> nextCursor = page.hasMore() ? Optional.of(encodeReadCursor(repositoryId, required, file, page.position())) : Optional.empty();
            return new SemanticQueryContract.GitFileContent(repositoryId.value(), required.snapshotId(), required.revision(), file.path(), file.pathKey(),
                    file.contentStatus(), page.content(), page.startLine(), page.endLine(), start.lineComplete(), page.endLineComplete(), nextCursor);
        } catch (MongoException | DataAccessException exception) {
            throw new SemanticIndexUnavailableException(exception);
        } catch (RepositoryNotFoundException | GitEvidenceNotFoundException | GitEvidenceNotReadyException | IndexContractMismatchException | IllegalArgumentException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IndexContractMismatchException();
        }
    }

    public SemanticQueryContract.GitTextSearchResult searchText(SemanticQueryContract.GitTextSearchRequest request) {
        SemanticQueryContract.GitTextSearchRequest required = Objects.requireNonNull(request, "git text search request is required");
        RepositoryId repositoryId = new RepositoryId(required.repositoryId());
        try {
            authorize(repositoryId);
            Document manifest = snapshotManifest(repositoryId, required.snapshotId(), required.revision());
            SearchPosition position = required.cursor().map(cursor -> decodeSearchCursor(cursor, repositoryId, required)).orElse(SearchPosition.initial());
            List<SemanticQueryContract.GitTextMatch> matches = new ArrayList<>();
            SearchBudget budget = new SearchBudget();
            String directory = required.directory().orElse("");
            Bson searchScope = Filters.and(Filters.eq("repoId", repositoryId.value()), Filters.eq("snapshotId", required.snapshotId()),
                    Filters.eq("contentStatus", "TEXT"), Filters.eq("contentKind", "CODE"),
                    Filters.gte("ordinal", position.fileOrdinal()), directoryFilter(directory));
            if (required.cursor().isPresent()) {
                validateSearchCursorTarget(repositoryId, required.snapshotId(), position, directory, manifest);
            }
            for (Document row : template.getCollection(IndexCollections.GIT_SNAPSHOT_FILES).find(searchScope).sort(Sorts.ascending("ordinal"))
                    .maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                SnapshotFile file = validatedFile(row, manifest);
                if (file.ordinal() == position.fileOrdinal()) {
                    if (file.chunkCount() == 0L) {
                        position = new SearchPosition(file.ordinal() + 1L, 0L, 0);
                        continue;
                    }
                }
                SearchStart start;
                try {
                    start = file.ordinal() == position.fileOrdinal()
                            ? decodeSearchPosition(repositoryId, required.snapshotId(), file, position, budget)
                            : new SearchStart(firstPosition(repositoryId, required.snapshotId(), file), Optional.empty());
                } catch (SearchBudgetExhaustedException exception) {
                    return searchResult(repositoryId, required, matches, false, file.ordinal(), position.chunkOrdinal(), position.byteOffset(),
                            coverage(repositoryId, required.snapshotId(), required.directory().orElse(""), manifest));
                }
                SearchPage page = searchFile(repositoryId, required.snapshotId(), file, start, required.query(), required.limit() - matches.size(), budget);
                matches.addAll(page.matches());
                if (!page.complete()) {
                    return searchResult(repositoryId, required, matches, false, file.ordinal(), page.position().chunkOrdinal(), page.position().byteOffset(),
                            coverage(repositoryId, required.snapshotId(), required.directory().orElse(""), manifest));
                }
                if (matches.size() >= required.limit()) {
                    Optional<SnapshotFile> next = nextSearchFile(repositoryId, required.snapshotId(), directory, file.ordinal(), manifest);
                    if (next.isPresent()) {
                        return searchResult(repositoryId, required, matches, false, next.orElseThrow().ordinal(), 0L, 0,
                                coverage(repositoryId, required.snapshotId(), required.directory().orElse(""), manifest));
                    }
                    return new SemanticQueryContract.GitTextSearchResult(repositoryId.value(), required.snapshotId(), required.revision(), List.copyOf(matches),
                            true, Optional.empty(), coverage(repositoryId, required.snapshotId(), required.directory().orElse(""), manifest));
                }
                position = new SearchPosition(file.ordinal() + 1L, 0L, 0);
            }
            return new SemanticQueryContract.GitTextSearchResult(repositoryId.value(), required.snapshotId(), required.revision(), List.copyOf(matches),
                    true, Optional.empty(), coverage(repositoryId, required.snapshotId(), required.directory().orElse(""), manifest));
        } catch (MongoException | DataAccessException exception) {
            throw new SemanticIndexUnavailableException(exception);
        } catch (RepositoryNotFoundException | GitEvidenceNotFoundException | GitEvidenceNotReadyException | IndexContractMismatchException | IllegalArgumentException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IndexContractMismatchException();
        }
    }

    private Document snapshotManifest(RepositoryId repositoryId, String snapshotId, String revision) {
        Document manifest = ready(findManifest(repositoryId, new GitEvidenceId(snapshotId)), "SNAPSHOT");
        if (!revision.equals(requiredText(manifest, "revision"))) {
            throw new IllegalArgumentException("snapshot revision does not match request");
        }
        if (requiredLong(manifest, "total") < 0L || !requiredText(manifest, "contentDigest").matches("[0-9a-f]{64}")) {
            throw new IndexContractMismatchException();
        }
        Document coverage = manifest.get("contentCoverage", Document.class);
        if (Objects.isNull(coverage) || requiredLong(coverage, "textBytes") < 0L || requiredLong(coverage, "textEntries") < 0L
                || requiredLong(coverage, "entryCount") != requiredLong(manifest, "total") || requiredLong(manifest, "fileTextBytesLimit") < 0L
                || requiredLong(manifest, "snapshotTextBytesLimit") < 0L) {
            throw new IndexContractMismatchException();
        }
        if ("STANDALONE".equals(requiredText(manifest, "scope"))
                && currentSourceSnapshot(repositoryId, snapshotId, revision, manifest)) {
            return manifest;
        }
        requireReadyComparisonOwner(repositoryId, snapshotId, revision, manifest);
        return manifest;
    }

    private boolean currentSourceSnapshot(RepositoryId repositoryId, String snapshotId, String revision, Document snapshot) {
        Document repository = template.getCollection(IndexCollections.REPOSITORIES).find(Filters.eq("repoId", repositoryId.value()))
                .maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS).first();
        if (Objects.isNull(repository)) {
            return false;
        }
        Document pointer = repository.get("currentPointer", Document.class);
        if (Objects.isNull(pointer) || !revision.equals(pointer.getString("revision"))) {
            return false;
        }
        Document generation = generation(repositoryId, revision, pointer.getString("generationId"), pointer.getString("manifestDigest"));
        if (Objects.isNull(generation)) {
            throw new IndexContractMismatchException();
        }
        snapshot.put("_selectedPolicy", requireBoundSource(repositoryId, revision, snapshot, generation, Optional.of(snapshotId)));
        return true;
    }

    private Document generation(RepositoryId repositoryId, String revision, String generationId, String digest) {
        if (Objects.isNull(generationId) || Objects.isNull(digest)) {
            throw new IndexContractMismatchException();
        }
        return template.getCollection(IndexCollections.GENERATION_MANIFESTS).find(Filters.and(
                Filters.eq("repoId", repositoryId.value()), Filters.eq("sourceRevision", revision),
                Filters.eq("generationId", generationId), Filters.eq("identityDigest", digest),
                Filters.eq("schemaVersion", IndexSchemaContract.SCHEMA_VERSION), Filters.eq("writeState", "SEALED_VALID")))
                .maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS).first();
    }

    private SourceEvidencePolicy requireBoundSource(RepositoryId repositoryId, String revision, Document snapshot,
            Document generation, Optional<String> sourceSnapshotId) {
        if (Objects.isNull(generation)) {
            throw new IndexContractMismatchException();
        }
        generations.require(
                new SelectedGeneration(repositoryId, new RepositoryRevision(revision),
                        new GenerationId(requiredText(generation, "generationId")),
                        new ManifestDigest(requiredText(generation, "identityDigest"))),
                SelectedGenerationGuard.ALL_PROJECTIONS);
        try {
            SourceSnapshotMembership membership = template.getConverter().read(SourceSnapshotMembership.class,
                    requiredDocument(generation, "sourceSnapshot"));
            SourceEvidencePolicy policy = SourceEvidenceDocumentCodec.decodePolicy(
                    requiredDocument(generation, "sourcePolicy"));
            ProjectGuideMembership guide = SourceEvidenceDocumentCodec.decodeGuide(
                    requiredDocument(generation, "projectGuide"));
            if (!revision.equals(membership.revision().value())
                    || !requiredText(generation, "generationId").equals(requiredText(snapshot, "sourceGenerationId"))
                    || !membership.policyFingerprint().equals(policy.fingerprint())
                    || !membership.policyFingerprint().equals(requiredText(snapshot, "policyFingerprint"))
                    || !membership.contentDigest().equals(requiredText(snapshot, "contentDigest"))
                    || !Objects.equals(snapshot.get("projectGuide"), generation.get("projectGuide"))
                    || guide.path().isPresent() && !guide.path().equals(policy.projectGuidePath())
                    || (guide.state() == com.java.semantic.model.source.ProjectGuideState.DISABLED) != policy.projectGuidePath().isEmpty()
                    || guide.importedRevision().filter(value -> !revision.equals(value.value())).isPresent()
                    || sourceSnapshotId.filter(id -> !id.equals(membership.snapshotId().value())).isPresent()) {
                throw new IndexContractMismatchException();
            }
            return policy;
        } catch (IllegalArgumentException exception) {
            throw new IndexContractMismatchException();
        }
    }

    private void requireReadyComparisonOwner(RepositoryId repositoryId, String snapshotId, String revision, Document snapshot) {
        String ownerJobId = requiredText(snapshot, "ownerJobId");
        Bson endpoint = Filters.or(Filters.and(Filters.eq("previousSnapshotId", snapshotId), Filters.eq("previous", revision)),
                Filters.and(Filters.eq("currentSnapshotId", snapshotId), Filters.eq("current", revision)));
        List<Document> parents = new ArrayList<>();
        for (Document parent : template.getCollection(IndexCollections.GIT_EVIDENCE_MANIFESTS).find(Filters.and(
                Filters.eq("repoId", repositoryId.value()), Filters.eq("kind", "COMPARISON"), Filters.eq("ownerJobId", ownerJobId), endpoint)).limit(2)
                .maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
            parents.add(parent);
        }
        if (parents.size() != 1) {
            throw new IndexContractMismatchException();
        }
        String parentState = requiredText(parents.getFirst(), "state");
        if ("PREPARING".equals(parentState) || "FAILED".equals(parentState)) {
            throw new GitEvidenceNotReadyException();
        }
        if (!"READY".equals(parentState)) {
            throw new IndexContractMismatchException();
        }
        Document parent = ready(parents.getFirst(), "COMPARISON");
        GitEvidenceOwnership ownership = ownership(snapshot);
        if (!ownership.equals(ownership(parent)) || !ownerJobId.equals(requiredText(parent, "ownerJobId"))) {
            throw new IndexContractMismatchException();
        }
        try {
            GitSnapshotId requestedSnapshotId = new GitSnapshotId(snapshotId);
            GitSnapshotId previousSnapshotId = new GitSnapshotId(requiredText(parent, "previousSnapshotId"));
            GitSnapshotId currentSnapshotId = new GitSnapshotId(requiredText(parent, "currentSnapshotId"));
            Optional<RepositoryRevision> previous = Optional.ofNullable(parent.getString("previous")).map(RepositoryRevision::new);
            RepositoryRevision current = new RepositoryRevision(requiredText(parent, "current"));
            boolean requestedPrevious = requestedSnapshotId.equals(previousSnapshotId)
                    && previous.map(value -> Objects.equals(revision, value.value())).orElse(Objects.isNull(revision));
            boolean requestedCurrent = requestedSnapshotId.equals(currentSnapshotId) && Objects.equals(revision, current.value());
            if (requestedPrevious == requestedCurrent) {
                throw new IndexContractMismatchException();
            }
            GitSnapshotId siblingSnapshotId = requestedPrevious ? currentSnapshotId : previousSnapshotId;
            Optional<RepositoryRevision> siblingRevision = requestedPrevious ? Optional.of(current) : previous;
            requireReadySiblingSnapshot(repositoryId, siblingSnapshotId, siblingRevision, ownerJobId, ownership);
            authorizeReviewMembership(repositoryId, ownership, ownerJobId, new GitComparisonId(requiredText(parent, "evidenceId")), previousSnapshotId,
                    currentSnapshotId, previous, current);
            bindComparisonSources(repositoryId, parent, previousSnapshotId, currentSnapshotId, previous, current, ownership);
            snapshot.put("_selectedPolicy", requiredPolicy(parent, requestedPrevious ? "beforePolicy" : "afterPolicy"));
        } catch (IllegalArgumentException exception) {
            throw new IndexContractMismatchException();
        }
    }

    private void requireReadySiblingSnapshot(RepositoryId repositoryId, GitSnapshotId siblingSnapshotId,
                                             Optional<RepositoryRevision> siblingRevision, String ownerJobId,
                                             GitEvidenceOwnership ownership) {
        Document sibling = findManifest(repositoryId, new GitEvidenceId(siblingSnapshotId.value()));
        if (Objects.isNull(sibling) || !"SNAPSHOT".equals(requiredText(sibling, "kind")) || !"READY".equals(requiredText(sibling, "state"))
                || !repositoryId.value().equals(requiredText(sibling, "repoId")) || !ownerJobId.equals(requiredText(sibling, "ownerJobId"))
                || !Objects.equals(siblingRevision.map(RepositoryRevision::value).orElse(null), sibling.getString("revision"))
                || !ownership.equals(ownership(sibling))) {
            throw new IndexContractMismatchException();
        }
    }

    private void bindComparisonSources(RepositoryId repositoryId, Document comparison, GitSnapshotId beforeId,
            GitSnapshotId afterId, Optional<RepositoryRevision> before, RepositoryRevision after, GitEvidenceOwnership ownership) {
        Document afterSnapshot = readySnapshot(repositoryId, afterId, Optional.of(after), requiredText(comparison, "ownerJobId"), ownership);
        Document beforeGeneration = null;
        Document afterGeneration;
        if (ownership.scope() == GitPublicationScope.REVIEW) {
            ReviewManifestDocument review = reviews.requireReady(repositoryId, ownership.reviewId().orElseThrow());
            if (before.isPresent()) {
                beforeGeneration = sealedEndpoint(repositoryId, before.orElseThrow().value(),
                        review.before().orElseThrow(IndexContractMismatchException::new).generation().selected());
            }
            afterGeneration = sealedEndpoint(repositoryId, after.value(), review.after().orElseThrow().generation().selected());
        } else {
            if (before.isPresent()) {
                beforeGeneration = sealedSourceForSnapshot(repositoryId, before.orElseThrow().value(), beforeId.value());
            }
            afterGeneration = sealedSourceForSnapshot(repositoryId, after.value(), afterId.value());
        }
        if (before.isPresent()) {
            Document beforeSnapshot = readySnapshot(repositoryId, beforeId, before, requiredText(comparison, "ownerJobId"), ownership);
            comparison.put("beforePolicy", requireBoundSource(repositoryId, before.orElseThrow().value(), beforeSnapshot,
                    beforeGeneration, Optional.empty()));
        } else if (beforeId.equals(afterId)) {
            throw new IndexContractMismatchException();
        }
        comparison.put("afterPolicy", requireBoundSource(repositoryId, after.value(), afterSnapshot,
                afterGeneration, Optional.empty()));
    }

    private Document sealedEndpoint(RepositoryId repositoryId, String revision,
            SelectedGeneration selected) {
        return generation(repositoryId, revision, selected.generationId().value(), selected.manifestDigest().value());
    }

    private Document sealedSourceForSnapshot(RepositoryId repositoryId, String revision, String snapshotId) {
        Document snapshot = ready(findManifest(repositoryId, new GitEvidenceId(snapshotId)), "SNAPSHOT");
        Document generation = template.getCollection(IndexCollections.GENERATION_MANIFESTS).find(Filters.and(
                Filters.eq("repoId", repositoryId.value()), Filters.eq("sourceRevision", revision),
                Filters.eq("generationId", requiredText(snapshot, "sourceGenerationId")),
                Filters.eq("schemaVersion", IndexSchemaContract.SCHEMA_VERSION), Filters.eq("writeState", "SEALED_VALID")))
                .maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS).first();
        if (Objects.isNull(generation)) {
            throw new IndexContractMismatchException();
        }
        return generation;
    }

    private static SourceEvidencePolicy requiredPolicy(Document document, String field) {
        Object value = document.get(field);
        if (!(value instanceof SourceEvidencePolicy policy)) {
            throw new IndexContractMismatchException();
        }
        return policy;
    }

    private static GitEvidenceId canonicalGitEvidenceId(String value) {
        try {
            return new GitEvidenceId(value);
        } catch (IllegalArgumentException exception) {
            throw new IndexContractMismatchException();
        }
    }

    private FileEntries directEntries(RepositoryId repositoryId, String snapshotId, String directory, int offset, int limit, Document manifest) {
        validateDirectory(directory);
        SourceEvidencePolicy policy = requiredPolicy(manifest, "_selectedPolicy");
        List<String> allowed = new ArrayList<>(policy.selectedCodePaths());
        ProjectGuideMembership guide = SourceEvidenceDocumentCodec.decodeGuide(requiredDocument(manifest, "projectGuide"));
        if (guide.state() == com.java.semantic.model.source.ProjectGuideState.AVAILABLE) {
            allowed.add(guide.path().orElseThrow());
        }
        String prefix = directory.isEmpty() ? "" : directory + "/";
        List<Bson> pipeline = new ArrayList<>();
        pipeline.add(new Document("$match", Filters.and(Filters.eq("repoId", repositoryId.value()),
                Filters.eq("snapshotId", snapshotId), Filters.in("path", allowed), directoryFilter(directory))));
        Object remaining = directory.isEmpty() ? "$path" : new Document("$cond", List.of(
                new Document("$eq", List.of(new Document("$indexOfCP", List.of("$path", prefix)), 0)),
                new Document("$substrCP", List.of("$path", prefix.codePointCount(0, prefix.length()), 2_147_483_647)), "$path"));
        pipeline.add(new Document("$project", new Document("path", 1).append("pathKey", 1).append("ordinal", 1).append("byteLength", 1).append("contentStatus", 1)
                .append("remaining", remaining)));
        pipeline.add(new Document("$project", new Document("path", 1).append("pathKey", 1).append("ordinal", 1).append("byteLength", 1).append("contentStatus", 1)
                .append("child", new Document("$arrayElemAt", List.of(new Document("$split", List.of("$remaining", "/")), 0)))
                .append("nested", new Document("$ne", List.of(new Document("$indexOfCP", List.of("$remaining", "/")), -1)))));
        pipeline.add(new Document("$project", new Document("path", 1).append("pathKey", 1).append("ordinal", 1).append("byteLength", 1).append("contentStatus", 1)
                .append("entryPath", new Document("$cond", List.of("$nested", new Document("$concat", List.of(prefix, "$child")), "$path")))
                .append("entryType", new Document("$cond", List.of("$nested", "DIRECTORY", "FILE")))));
        pipeline.add(new Document("$sort", new Document("ordinal", 1)));
        pipeline.add(new Document("$group", new Document("_id", "$entryPath").append("pathKey", new Document("$first", "$pathKey"))
                .append("byteLength", new Document("$first", "$byteLength")).append("contentStatus", new Document("$first", "$contentStatus"))
                .append("entryType", new Document("$first", "$entryType"))));
        pipeline.add(new Document("$sort", new Document("_id", 1)));
        pipeline.add(new Document("$facet", new Document("items", List.of(new Document("$skip", offset), new Document("$limit", limit + 1L)))
                .append("total", List.of(new Document("$count", "value")))));
        Document result = template.getCollection(IndexCollections.GIT_SNAPSHOT_FILES).aggregate(pipeline).maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS).first();
        if (Objects.isNull(result)) {
            throw new IndexContractMismatchException();
        }
        List<Document> rows = result.getList("items", Document.class, List.of());
        List<SemanticQueryContract.GitFileItem> items = new ArrayList<>();
        for (Document row : rows) {
            String entryType = requiredText(row, "entryType");
            String path = requiredText(row, "_id");
            if ("DIRECTORY".equals(entryType)) {
                items.add(new SemanticQueryContract.GitFileItem(path, java.util.HexFormat.of().formatHex(path.getBytes(StandardCharsets.UTF_8)), "DIRECTORY", 0L, "DIRECTORY"));
            } else if ("FILE".equals(entryType)) {
                SnapshotFile file = file(repositoryId, snapshotId, path, manifest);
                if (!file.pathKey().equals(requiredText(row, "pathKey"))
                        || file.byteLength() != requiredLong(row, "byteLength")
                        || !file.contentStatus().equals(requiredText(row, "contentStatus"))) {
                    throw new IndexContractMismatchException();
                }
                items.add(new SemanticQueryContract.GitFileItem(path, file.pathKey(), "FILE", file.byteLength(), file.contentStatus()));
            } else {
                throw new IndexContractMismatchException();
            }
        }
        List<Document> totals = result.getList("total", Document.class, List.of());
        long total = totals.isEmpty() ? 0L : requiredLong(totals.getFirst(), "value");
        boolean hasMore = items.size() > limit;
        if (hasMore) {
            items.removeLast();
        }
        return new FileEntries(List.copyOf(items), total, hasMore);
    }

    private static Bson directoryFilter(String directory) {
        validateDirectory(directory);
        if (directory.isEmpty()) {
            return new Document();
        }
        String prefixKey = java.util.HexFormat.of().formatHex((directory + "/").getBytes(StandardCharsets.UTF_8));
        return Filters.expr(new Document("$eq", List.of(new Document("$substrCP", List.of("$pathKey", 0, prefixKey.length())), prefixKey)));
    }

    private SnapshotFile file(RepositoryId repositoryId, String snapshotId, String path, Document manifest) {
        List<Document> rows = new ArrayList<>();
        for (Document row : template.getCollection(IndexCollections.GIT_SNAPSHOT_FILES).find(Filters.and(Filters.eq("repoId", repositoryId.value()),
                Filters.eq("snapshotId", snapshotId), Filters.eq("path", path))).limit(2).maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
            rows.add(row);
        }
        if (rows.isEmpty()) {
            throw new GitEvidenceNotFoundException();
        }
        if (rows.size() != 1) {
            throw new IndexContractMismatchException();
        }
        SnapshotFile file = validatedFile(rows.getFirst(), manifest);
        if (file.ordinal() >= requiredLong(manifest, "total") || ("TEXT".equals(file.contentStatus())
                && file.byteLength() > requiredLong(manifest, "fileTextBytesLimit"))) {
            throw new IndexContractMismatchException();
        }
        return file;
    }

    private SnapshotFile validatedFile(Document row, Document manifest) {
        SnapshotFile file = snapshotFile(row);
        SourceEvidencePolicy policy = requiredPolicy(manifest, "_selectedPolicy");
        String kind = requiredText(row, "contentKind");
        if (!policy.fingerprint().equals(requiredText(row, "policyFingerprint"))
                || !List.of("100644", "100755").contains(requiredText(row, "mode"))) {
            throw new IndexContractMismatchException();
        }
        if ("CODE".equals(kind) && policy.allowsCode(file.path()) && "TEXT".equals(file.contentStatus())) {
            return file;
        }
        if ("PROJECT_GUIDE".equals(kind) && policy.allowsGuide(file.path())) {
            ProjectGuideMembership guide = SourceEvidenceDocumentCodec.decodeGuide(
                    requiredDocument(manifest, "projectGuide"));
            if (guide.state() == com.java.semantic.model.source.ProjectGuideState.AVAILABLE
                    && guide.path().filter(file.path()::equals).isPresent()
                    && guide.digest().filter(requiredText(row, "checksum")::equals).isPresent()
                    && guide.importedRevision().filter(revision -> revision.value().equals(requiredText(manifest, "revision"))).isPresent()
                    && "TEXT".equals(file.contentStatus())) {
                return file;
            }
        }
        throw new IndexContractMismatchException();
    }

    private static SnapshotFile snapshotFile(Document row) {
        String path = requiredText(row, "path");
        byte[] rawPath = requiredBytes(row, "rawPath");
        String pathKey = requiredText(row, "pathKey");
        long ordinal = requiredLong(row, "ordinal");
        long byteLength = requiredLong(row, "byteLength");
        long chunkCount = requiredLong(row, "chunkCount");
        String contentStatus = requiredText(row, "contentStatus");
        if (ordinal < 0L || byteLength < 0L || chunkCount < 0L || rawPath.length == 0 || !pathKey.equals(java.util.HexFormat.of().formatHex(rawPath))
                || !path.equals(displayPath(rawPath)) || !requiredText(row, "mode").matches("[0-7]{6}")
                || !requiredText(row, "blobId").matches("[0-9a-f]{40}") || !requiredText(row, "checksum").matches("[0-9a-f]{64}")) {
            throw new IndexContractMismatchException();
        }
        try {
            GitFileContentStatus.valueOf(contentStatus);
        } catch (IllegalArgumentException exception) {
            throw new IndexContractMismatchException();
        }
        if (("TEXT".equals(contentStatus) && ((byteLength == 0L) != (chunkCount == 0L))) || (!"TEXT".equals(contentStatus) && chunkCount != 0L)) {
            throw new IndexContractMismatchException();
        }
        return new SnapshotFile(path, pathKey, ordinal, byteLength, chunkCount, contentStatus);
    }

    private SemanticQueryContract.GitSnapshotCoverage coverage(RepositoryId repositoryId, String snapshotId, String directory, Document manifest) {
        validateDirectory(directory);
        List<Document> pipeline = new ArrayList<>();
        pipeline.add(new Document("$match", new Document("repoId", repositoryId.value()).append("snapshotId", snapshotId)));
        if (!directory.isEmpty()) {
            pipeline.add(new Document("$match", directoryFilter(directory)));
        }
        pipeline.add(new Document("$group", new Document("_id", "$contentStatus").append("count", new Document("$sum", 1L))));
        Map<String, Long> counts = new java.util.HashMap<>();
        for (Document row : template.getCollection(IndexCollections.GIT_SNAPSHOT_FILES).aggregate(pipeline).maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
            String status = requiredText(row, "_id");
            long count = requiredLong(row, "count");
            try {
                GitFileContentStatus.valueOf(status);
            } catch (IllegalArgumentException exception) {
                throw new IndexContractMismatchException();
            }
            Long priorCount = counts.put(status, count);
            if (count < 0L || Objects.nonNull(priorCount)) {
                throw new IndexContractMismatchException();
            }
        }
        long text = count(counts, "TEXT");
        long inventory = counts.values().stream().mapToLong(Long::longValue).sum();
        if (directory.isEmpty() && (inventory != requiredLong(manifest, "total") || text != requiredLong(manifest.get("contentCoverage", Document.class), "textEntries"))) {
            throw new IndexContractMismatchException();
        }
        return new SemanticQueryContract.GitSnapshotCoverage(inventory, text, count(counts, "BINARY"), count(counts, "UNSUPPORTED_ENCODING"),
                count(counts, "TOO_LARGE"), count(counts, "SYMLINK"), count(counts, "SUBMODULE"), count(counts, "LFS_POINTER"), count(counts, "UNSUPPORTED_PATH"));
    }

    private ReadPosition positionForLine(RepositoryId repositoryId, String snapshotId, SnapshotFile file, int wantedLine) {
        ReadPosition position = firstPosition(repositoryId, snapshotId, file);
        while (position.line() < wantedLine) {
            ChunkData chunk = chunkData(repositoryId, snapshotId, file, position.chunkOrdinal(), position.byteOffset(), position.line(), position.column());
            int character = charIndexAtByteOffset(chunk.text(), Math.toIntExact(position.byteOffset() - chunk.byteOffset()));
            while (character < chunk.text().length() && position.line() < wantedLine) {
                position = advance(position, chunk.text().codePointAt(character));
                character += Character.charCount(chunk.text().codePointAt(character));
            }
            if (position.line() == wantedLine) {
                return position;
            }
            if (position.chunkOrdinal() + 1L >= file.chunkCount()) {
                break;
            }
            position = nextChunkPosition(repositoryId, snapshotId, file, position, chunk);
        }
        if (position.line() != wantedLine) {
            throw new IllegalArgumentException("start line is outside the file");
        }
        return position;
    }

    private ReadPosition firstPosition(RepositoryId repositoryId, String snapshotId, SnapshotFile file) {
        if (file.chunkCount() == 0L) {
            return new ReadPosition(0L, 0, 1, 1, true);
        }
        return checkpoint(repositoryId, snapshotId, file, 0L);
    }

    private ReadPage readPage(RepositoryId repositoryId, String snapshotId, SnapshotFile file, ReadPosition start, int maxLines) {
        StringBuilder content = new StringBuilder();
        ReadPosition position = start;
        int responseBytes = 0;
        int returnedLines = 0;
        while (position.chunkOrdinal() < file.chunkCount()) {
            ChunkData chunk = chunkData(repositoryId, snapshotId, file, position.chunkOrdinal(), position.byteOffset(), position.line(), position.column());
            int character = charIndexAtByteOffset(chunk.text(), Math.toIntExact(position.byteOffset() - chunk.byteOffset()));
            while (character < chunk.text().length()) {
                int codePoint = chunk.text().codePointAt(character);
                int bytes = utf8Bytes(codePoint);
                if (responseBytes + bytes > MAX_RESPONSE_BYTES) {
                    return page(content, start, position, true);
                }
                content.appendCodePoint(codePoint);
                responseBytes += bytes;
                position = advance(position, codePoint);
                character += Character.charCount(codePoint);
                if (codePoint == '\n' && ++returnedLines == maxLines) {
                    boolean hasMore = position.byteOffset() < file.byteLength();
                    if (hasMore && character == chunk.text().length()) {
                        position = nextChunkPosition(repositoryId, snapshotId, file, position, chunk);
                    }
                    return page(content, start, position, hasMore);
                }
            }
            if (position.byteOffset() == file.byteLength()) {
                return page(content, start, position, false);
            }
            position = nextChunkPosition(repositoryId, snapshotId, file, position, chunk);
        }
        return page(content, start, position, false);
    }

    private static ReadPage page(StringBuilder content, ReadPosition start, ReadPosition end, boolean hasMore) {
        if (content.isEmpty()) {
            return new ReadPage("", 0, 0, true, hasMore, end);
        }
        boolean complete = content.charAt(content.length() - 1) == '\n' || !hasMore;
        int endLine = content.charAt(content.length() - 1) == '\n' ? end.line() - 1 : end.line();
        return new ReadPage(content.toString(), start.line(), endLine, complete, hasMore, end);
    }

    private SearchPage searchFile(RepositoryId repositoryId, String snapshotId, SnapshotFile file, SearchStart start, String query, int remaining,
                                  SearchBudget budget) {
        List<SemanticQueryContract.GitTextMatch> matches = new ArrayList<>();
        KmpMatcher matcher = new KmpMatcher(query);
        ReadPosition position = start.position();
        Optional<ChunkData> decodedStart = start.chunk();
        while (position.byteOffset() < file.byteLength()) {
            boolean reusedDecodedChunk = decodedStart.isPresent();
            ChunkData chunk;
            if (reusedDecodedChunk) {
                chunk = decodedStart.orElseThrow();
            } else {
                chunk = chunkData(repositoryId, snapshotId, file, position.chunkOrdinal(), position.byteOffset(), position.line(), position.column(), budget);
            }
            decodedStart = Optional.empty();
            if (Objects.isNull(chunk)) {
                return new SearchPage(List.copyOf(matches), false, matcher.firstPosition().orElse(position));
            }
            int character = charIndexAtByteOffset(chunk.text(), Math.toIntExact(position.byteOffset() - chunk.byteOffset()));
            while (character < chunk.text().length()) {
                int codePoint = chunk.text().codePointAt(character);
                ReadPosition tokenPosition = position;
                position = advance(position, codePoint);
                character += Character.charCount(codePoint);
                Optional<List<SearchToken>> match = matcher.accept(new SearchToken(codePoint, tokenPosition, chunk));
                if (match.isPresent()) {
                    List<SearchToken> tokens = match.orElseThrow();
                    SearchToken first = tokens.getFirst();
                    Map<Long, ChunkData> reusableChunks = new java.util.HashMap<>();
                    for (SearchToken token : tokens) {
                        reusableChunks.put(token.position().chunkOrdinal(), token.chunk());
                    }
                    Snippet snippet = snippetAt(repositoryId, snapshotId, file, first.position(), budget, reusableChunks);
                    matches.add(new SemanticQueryContract.GitTextMatch(file.path(), file.pathKey(), first.position().line(), first.position().column(),
                            snippet.text(), snippet.truncated()));
                    if (matches.size() == remaining) {
                        ReadPosition next = advance(first.position(), first.codePoint());
                        if (next.byteOffset() == file.byteLength()) {
                            return new SearchPage(List.copyOf(matches), true, next);
                        }
                        if (next.byteOffset() == chunk.byteOffset() + chunk.bytes().length) {
                            next = nextChunkPosition(repositoryId, snapshotId, file, next, chunk);
                        }
                        return new SearchPage(List.copyOf(matches), false, next);
                    }
                }
            }
            if (position.byteOffset() < file.byteLength()) {
                position = nextChunkPosition(repositoryId, snapshotId, file, position, chunk);
            }
        }
        return new SearchPage(List.copyOf(matches), true, position);
    }

    private Snippet snippetAt(RepositoryId repositoryId, String snapshotId, SnapshotFile file, ReadPosition start, SearchBudget budget,
                              Map<Long, ChunkData> reusableChunks) {
        StringBuilder text = new StringBuilder();
        ReadPosition position = start;
        boolean omittedPrefix = position.column() > 1;
        while (position.byteOffset() < file.byteLength() && text.codePointCount(0, text.length()) < 160) {
            ChunkData chunk = reusableChunks.get(position.chunkOrdinal());
            if (Objects.isNull(chunk)) {
                chunk = chunkData(repositoryId, snapshotId, file, position.chunkOrdinal(), position.byteOffset(), position.line(), position.column(), budget);
            }
            if (Objects.isNull(chunk)) {
                return new Snippet(text.toString(), true);
            }
            int character = charIndexAtByteOffset(chunk.text(), Math.toIntExact(position.byteOffset() - chunk.byteOffset()));
            while (character < chunk.text().length() && text.codePointCount(0, text.length()) < 160) {
                int codePoint = chunk.text().codePointAt(character);
                if (codePoint == '\n') {
                    return new Snippet(text.toString(), omittedPrefix);
                }
                text.appendCodePoint(codePoint);
                position = advance(position, codePoint);
                character += Character.charCount(codePoint);
            }
            if (position.byteOffset() < file.byteLength() && character == chunk.text().length()) {
                position = nextChunkPosition(repositoryId, snapshotId, file, position, chunk);
            }
        }
        return new Snippet(text.toString(), omittedPrefix || position.byteOffset() < file.byteLength());
    }

    private SearchStart decodeSearchPosition(RepositoryId repositoryId, String snapshotId, SnapshotFile file, SearchPosition position,
                                             SearchBudget budget) {
        if (position.chunkOrdinal() < 0L || position.chunkOrdinal() >= file.chunkCount() || position.byteOffset() < 0 || position.byteOffset() >= file.byteLength()) {
            throw new IllegalArgumentException("search cursor is invalid");
        }
        ReadPosition checkpoint = checkpoint(repositoryId, snapshotId, file, position.chunkOrdinal(), budget);
        ChunkData chunk = chunkData(repositoryId, snapshotId, file, position.chunkOrdinal(), checkpoint.byteOffset(), checkpoint.line(), checkpoint.column(), budget);
        if (Objects.isNull(chunk)) {
            throw new SearchBudgetExhaustedException();
        }
        if (position.byteOffset() < checkpoint.byteOffset() || position.byteOffset() >= checkpoint.byteOffset() + chunk.bytes().length) {
            throw new IllegalArgumentException("search cursor is invalid");
        }
        int character = charIndexAtByteOffset(chunk.text(), Math.toIntExact(position.byteOffset() - checkpoint.byteOffset()));
        ReadPosition actual = checkpoint;
        for (int index = 0; index < character;) {
            int codePoint = chunk.text().codePointAt(index);
            actual = advance(actual, codePoint);
            index += Character.charCount(codePoint);
        }
        return new SearchStart(actual, Optional.of(chunk));
    }

    private void validateSearchCursorTarget(RepositoryId repositoryId, String snapshotId, SearchPosition position, String directory, Document manifest) {
        List<Document> rows = new ArrayList<>();
        Bson target = Filters.and(Filters.eq("repoId", repositoryId.value()), Filters.eq("snapshotId", snapshotId),
                Filters.eq("contentStatus", "TEXT"), Filters.eq("contentKind", "CODE"),
                Filters.eq("ordinal", position.fileOrdinal()), directoryFilter(directory));
        for (Document row : template.getCollection(IndexCollections.GIT_SNAPSHOT_FILES).find(target).limit(2)
                .maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
            rows.add(row);
        }
        if (rows.size() != 1) {
            throw new IllegalArgumentException("search cursor is invalid");
        }
        SnapshotFile file = validatedFile(rows.getFirst(), manifest);
        if (file.chunkCount() == 0L) {
            if (position.chunkOrdinal() != 0L || position.byteOffset() != 0) {
                throw new IllegalArgumentException("search cursor is invalid");
            }
            return;
        }
        if (position.chunkOrdinal() >= file.chunkCount() || position.byteOffset() >= file.byteLength()) {
            throw new IllegalArgumentException("search cursor is invalid");
        }
    }

    private Optional<SnapshotFile> nextSearchFile(RepositoryId repositoryId, String snapshotId, String directory, long ordinal, Document manifest) {
        Bson next = Filters.and(Filters.eq("repoId", repositoryId.value()), Filters.eq("snapshotId", snapshotId), Filters.eq("contentStatus", "TEXT"),
                Filters.eq("contentKind", "CODE"), Filters.gt("ordinal", ordinal), directoryFilter(directory));
        Document row = template.getCollection(IndexCollections.GIT_SNAPSHOT_FILES).find(next).sort(Sorts.ascending("ordinal")).limit(1)
                .maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS).first();
        return Objects.isNull(row) ? Optional.empty() : Optional.of(validatedFile(row, manifest));
    }

    private SemanticQueryContract.GitTextSearchResult searchResult(RepositoryId repositoryId, SemanticQueryContract.GitTextSearchRequest request,
                                                                     List<SemanticQueryContract.GitTextMatch> matches, boolean complete,
                                                                     long fileOrdinal, long chunkOrdinal, int byteOffset,
                                                                     SemanticQueryContract.GitSnapshotCoverage coverage) {
        return new SemanticQueryContract.GitTextSearchResult(repositoryId.value(), request.snapshotId(), request.revision(), List.copyOf(matches), complete,
                Optional.of(encodeSearchCursor(repositoryId, request, new SearchPosition(fileOrdinal, chunkOrdinal, byteOffset))), coverage);
    }

    private static long count(Map<String, Long> counts, String status) {
        return counts.getOrDefault(status, 0L);
    }

    private static boolean inDirectory(String path, String directory) {
        return directory.isEmpty() || path.startsWith(directory + "/");
    }

    private static void validateDirectory(String directory) {
        if (directory.indexOf('\u0000') >= 0 || directory.startsWith("/") || directory.endsWith("/") || directory.contains("//")
                || directory.equals(".") || directory.contains("../") || directory.startsWith("../")) {
            throw new IllegalArgumentException("directory is invalid");
        }
    }

    private static String encodeReadCursor(RepositoryId repositoryId, SemanticQueryContract.GitFileReadRequest request, SnapshotFile file, ReadPosition position) {
        return cursor(FILE_CURSOR_OPERATION, repositoryId.value(), request.snapshotId(), request.revision(), digest(file.pathKey()),
                Integer.toString(request.maxLines()), Long.toString(position.chunkOrdinal()), Integer.toString(position.byteOffset()));
    }

    private ReadPosition decodeReadCursor(String cursor, RepositoryId repositoryId, SemanticQueryContract.GitFileReadRequest request, SnapshotFile file) {
        List<String> values = cursor(cursor, 8, FILE_CURSOR_OPERATION);
        if (!repositoryId.value().equals(values.get(1)) || !request.snapshotId().equals(values.get(2)) || !request.revision().equals(values.get(3))
                || !digest(file.pathKey()).equals(values.get(4)) || !Integer.toString(request.maxLines()).equals(values.get(5))) {
            throw new IllegalArgumentException("file cursor is invalid");
        }
        try {
            long chunkOrdinal = Long.parseLong(values.get(6));
            int offset = Integer.parseInt(values.get(7));
            if (chunkOrdinal < 0L || chunkOrdinal >= file.chunkCount() || offset < 0 || offset >= file.byteLength()) {
                throw new IllegalArgumentException("file cursor is invalid");
            }
            return readPosition(repositoryId, request.snapshotId(), file, chunkOrdinal, offset);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("file cursor is invalid", exception);
        }
    }

    private ReadPosition readPosition(RepositoryId repositoryId, String snapshotId, SnapshotFile file, long chunkOrdinal, int byteOffset) {
        ReadPosition checkpoint = checkpoint(repositoryId, snapshotId, file, chunkOrdinal);
        ChunkData chunk = chunkData(repositoryId, snapshotId, file, chunkOrdinal, checkpoint.byteOffset(), checkpoint.line(), checkpoint.column());
        if (byteOffset < checkpoint.byteOffset() || byteOffset >= checkpoint.byteOffset() + chunk.bytes().length) {
            throw new IllegalArgumentException("file cursor is invalid");
        }
        int character = charIndexAtByteOffset(chunk.text(), Math.toIntExact(byteOffset - checkpoint.byteOffset()));
        ReadPosition position = checkpoint;
        for (int index = 0; index < character;) {
            int codePoint = chunk.text().codePointAt(index);
            position = advance(position, codePoint);
            index += Character.charCount(codePoint);
        }
        return position;
    }

    private ReadPosition checkpoint(RepositoryId repositoryId, String snapshotId, SnapshotFile file, long ordinal) {
        return checkpoint(repositoryId, snapshotId, file, ordinal, null);
    }

    private ReadPosition checkpoint(RepositoryId repositoryId, String snapshotId, SnapshotFile file, long ordinal, SearchBudget budget) {
        ReadPosition target = storedCheckpoint(repositoryId, snapshotId, file, ordinal);
        if (ordinal == 0L) {
            return target;
        }
        ReadPosition predecessor = storedCheckpoint(repositoryId, snapshotId, file, ordinal - 1L);
        ChunkData predecessorChunk = chunkData(repositoryId, snapshotId, file, ordinal - 1L, predecessor.byteOffset(), predecessor.line(), predecessor.column(), budget);
        if (Objects.isNull(predecessorChunk)) {
            throw new SearchBudgetExhaustedException();
        }
        ReadPosition derived = advanceThrough(predecessor, predecessorChunk.text());
        if (target.byteOffset() != derived.byteOffset() || target.line() != derived.line() || target.column() != derived.column()) {
            throw new IndexContractMismatchException();
        }
        return target;
    }

    private ReadPosition storedCheckpoint(RepositoryId repositoryId, String snapshotId, SnapshotFile file, long ordinal) {
        Document row = snapshotChunk(repositoryId, snapshotId, file, ordinal);
        int byteOffset = Math.toIntExact(requiredLong(row, "byteOffset"));
        int line = Math.toIntExact(requiredLong(row, "line"));
        int column = Math.toIntExact(requiredLong(row, "column"));
        if (line < 1 || column < 1 || byteOffset < 0 || byteOffset >= file.byteLength()
                || (ordinal == 0L && (byteOffset != 0 || line != 1 || column != 1))) {
            throw new IndexContractMismatchException();
        }
        return new ReadPosition(ordinal, byteOffset, line, column, column == 1);
    }

    private ChunkData chunkData(RepositoryId repositoryId, String snapshotId, SnapshotFile file, long ordinal, int byteOffset, int line, int column) {
        return chunkData(repositoryId, snapshotId, file, ordinal, byteOffset, line, column, null);
    }

    private ChunkData chunkData(RepositoryId repositoryId, String snapshotId, SnapshotFile file, long ordinal, int byteOffset, int line, int column,
                                SearchBudget budget) {
        Document row = snapshotChunk(repositoryId, snapshotId, file, ordinal);
        long offset = requiredLong(row, "byteOffset");
        int storedLine = Math.toIntExact(requiredLong(row, "line"));
        int storedColumn = Math.toIntExact(requiredLong(row, "column"));
        byte[] bytes = requiredBytes(row, "bytes");
        if (offset > Integer.MAX_VALUE || (byteOffset == offset && (storedLine != line || storedColumn != column)) || bytes.length == 0 || bytes.length > MAX_RESPONSE_BYTES || byteOffset < offset
                || byteOffset >= offset + bytes.length || offset + bytes.length > file.byteLength()) {
            throw new IndexContractMismatchException();
        }
        if (Objects.nonNull(budget) && !budget.accept(bytes.length)) {
            return null;
        }
        return new ChunkData(Math.toIntExact(offset), bytes, decodeUtf8(bytes));
    }

    private ReadPosition nextChunkPosition(RepositoryId repositoryId, String snapshotId, SnapshotFile file, ReadPosition position, ChunkData chunk) {
        long nextOrdinal = position.chunkOrdinal() + 1L;
        if (nextOrdinal >= file.chunkCount()) {
            if (position.byteOffset() != file.byteLength()) {
                throw new IndexContractMismatchException();
            }
            return position;
        }
        ReadPosition next = storedCheckpoint(repositoryId, snapshotId, file, nextOrdinal);
        if (next.byteOffset() != position.byteOffset() || next.line() != position.line() || next.column() != position.column()) {
            throw new IndexContractMismatchException();
        }
        return next;
    }

    private static ReadPosition advance(ReadPosition position, int codePoint) {
        int line = position.line();
        int column = position.column();
        if (codePoint == '\n') {
            line++;
            column = 1;
        } else {
            column++;
        }
        return new ReadPosition(position.chunkOrdinal(), Math.addExact(position.byteOffset(), utf8Bytes(codePoint)), line, column, codePoint == '\n');
    }

    private static ReadPosition advanceThrough(ReadPosition position, String text) {
        ReadPosition derived = position;
        for (int index = 0; index < text.length();) {
            int codePoint = text.codePointAt(index);
            derived = advance(derived, codePoint);
            index += Character.charCount(codePoint);
        }
        return derived;
    }

    private Document snapshotChunk(RepositoryId repositoryId, String snapshotId, SnapshotFile file, long ordinal) {
        Document chunk = template.getCollection(IndexCollections.GIT_SNAPSHOT_CHUNKS).find(Filters.and(Filters.eq("repoId", repositoryId.value()),
                Filters.eq("snapshotId", snapshotId), Filters.eq("pathKey", file.pathKey()), Filters.eq("ordinal", ordinal)))
                .maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS).first();
        if (Objects.isNull(chunk) || requiredLong(chunk, "ordinal") != ordinal || !repositoryId.value().equals(requiredText(chunk, "repoId"))
                || !snapshotId.equals(requiredText(chunk, "snapshotId")) || !file.pathKey().equals(requiredText(chunk, "pathKey"))) {
            throw new IndexContractMismatchException();
        }
        return chunk;
    }

    private static String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) {
            throw new IndexContractMismatchException();
        }
    }

    private static String encodeSearchCursor(RepositoryId repositoryId, SemanticQueryContract.GitTextSearchRequest request, SearchPosition position) {
        return cursor(SEARCH_CURSOR_OPERATION, repositoryId.value(), request.snapshotId(), request.revision(), digest(request.query()), digest(request.directory().orElse("")),
                Integer.toString(request.limit()), Long.toString(position.fileOrdinal()), Long.toString(position.chunkOrdinal()), Integer.toString(position.byteOffset()));
    }

    private static SearchPosition decodeSearchCursor(String cursor, RepositoryId repositoryId, SemanticQueryContract.GitTextSearchRequest request) {
        List<String> values = cursor(cursor, 10, SEARCH_CURSOR_OPERATION);
        if (!repositoryId.value().equals(values.get(1)) || !request.snapshotId().equals(values.get(2)) || !request.revision().equals(values.get(3))
                || !digest(request.query()).equals(values.get(4)) || !digest(request.directory().orElse("")).equals(values.get(5))
                || !Integer.toString(request.limit()).equals(values.get(6))) {
            throw new IllegalArgumentException("search cursor is invalid");
        }
        try {
            long fileOrdinal = Long.parseLong(values.get(7));
            long chunkOrdinal = Long.parseLong(values.get(8));
            int offset = Integer.parseInt(values.get(9));
            if (fileOrdinal < 0L || chunkOrdinal < 0L || offset < 0) {
                throw new IllegalArgumentException("search cursor is invalid");
            }
            return new SearchPosition(fileOrdinal, chunkOrdinal, offset);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("search cursor is invalid", exception);
        }
    }

    private static int charIndexAtByteOffset(String text, int target) {
        int bytes = 0;
        for (int character = 0; character < text.length();) {
            if (bytes == target) {
                return character;
            }
            int codePoint = text.codePointAt(character);
            bytes += utf8Bytes(codePoint);
            character += Character.charCount(codePoint);
        }
        if (bytes == target) {
            return text.length();
        }
        throw new IllegalArgumentException("cursor byte position is invalid");
    }

    private static int utf8Bytes(int codePoint) {
        if (codePoint <= 0x7f) {
            return 1;
        }
        if (codePoint <= 0x7ff) {
            return 2;
        }
        return codePoint <= 0xffff ? 3 : 4;
    }

    private static String digest(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (int index = 0; index < value.length(); index++) {
                char codeUnit = value.charAt(index);
                digest.update((byte) (codeUnit >>> 8));
                digest.update((byte) codeUnit);
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String cursor(String... values) {
        StringBuilder encoded = new StringBuilder();
        for (String value : values) {
            if (encoded.length() > 0) {
                encoded.append('.');
            }
            encoded.append(Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8)));
        }
        if (encoded.length() > MAX_CURSOR_CHARACTERS) {
            throw new IllegalArgumentException("cursor is too large");
        }
        return encoded.toString();
    }

    private static List<String> cursor(String value, int expectedSize, String operation) {
        try {
            if (value.length() > MAX_CURSOR_CHARACTERS) {
                throw new IllegalArgumentException("cursor is invalid");
            }
            String[] parts = value.split("\\.", -1);
            if (parts.length != expectedSize) {
                throw new IllegalArgumentException("cursor is invalid");
            }
            List<String> decoded = new ArrayList<>();
            for (String part : parts) {
                decoded.add(new String(Base64.getUrlDecoder().decode(part), StandardCharsets.UTF_8));
            }
            if (!operation.equals(decoded.getFirst())) {
                throw new IllegalArgumentException("cursor is invalid");
            }
            return List.copyOf(decoded);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("cursor is invalid", exception);
        }
    }

    private record FileEntries(List<SemanticQueryContract.GitFileItem> items, long total, boolean hasMore) { }
    private record SnapshotFile(String path, String pathKey, long ordinal, long byteLength, long chunkCount, String contentStatus) { }
    private record ReadPosition(long chunkOrdinal, int byteOffset, int line, int column, boolean lineComplete) { }
    private record ReadPage(String content, int startLine, int endLine, boolean endLineComplete, boolean hasMore, ReadPosition position) { }
    private record SearchPosition(long fileOrdinal, long chunkOrdinal, int byteOffset) {
        private static SearchPosition initial() { return new SearchPosition(0L, 0L, 0); }
    }
    private record SearchStart(ReadPosition position, Optional<ChunkData> chunk) { }
    private record ChunkData(int byteOffset, byte[] bytes, String text) { }
    private record SearchToken(int codePoint, ReadPosition position, ChunkData chunk) { }
    private static final class KmpMatcher {
        private final int[] query;
        private final int[] fallback;
        private final SearchToken[] tokens;
        private int matched;
        private long seen;

        private KmpMatcher(String text) {
            this.query = text.codePoints().toArray();
            this.fallback = fallback(query);
            this.tokens = new SearchToken[query.length];
        }

        private Optional<List<SearchToken>> accept(SearchToken token) {
            while (matched > 0 && query[matched] != token.codePoint()) {
                matched = fallback[matched - 1];
            }
            if (query[matched] == token.codePoint()) {
                matched++;
            }
            tokens[(int) (seen % tokens.length)] = token;
            seen++;
            if (matched != query.length) {
                return Optional.empty();
            }
            List<SearchToken> match = new ArrayList<>(query.length);
            long first = seen - query.length;
            for (int index = 0; index < query.length; index++) {
                match.add(tokens[(int) ((first + index) % tokens.length)]);
            }
            matched = fallback[matched - 1];
            return Optional.of(List.copyOf(match));
        }

        private Optional<ReadPosition> firstPosition() {
            if (seen == 0L || matched == 0) {
                return Optional.empty();
            }
            long first = seen - matched;
            SearchToken token = tokens[(int) (first % tokens.length)];
            return Optional.ofNullable(token).map(SearchToken::position);
        }

        private static int[] fallback(int[] query) {
            int[] values = new int[query.length];
            int length = 0;
            for (int index = 1; index < query.length; index++) {
                while (length > 0 && query[length] != query[index]) {
                    length = values[length - 1];
                }
                if (query[length] == query[index]) {
                    length++;
                }
                values[index] = length;
            }
            return values;
        }
    }
    private record SearchPage(List<SemanticQueryContract.GitTextMatch> matches, boolean complete, ReadPosition position) { }
    private record Snippet(String text, boolean truncated) { }
    private static final class SearchBudgetExhaustedException extends RuntimeException { }
    private static final class SearchBudget {
        private int bytes;
        private boolean accept(int additional) {
            if (bytes + additional > MAX_SEARCH_BYTES) { return false; }
            bytes += additional;
            return true;
        }
    }

    private void authorize(RepositoryId repositoryId) {
        readPolicy.requireGitEvidenceVisible(repositoryId);
    }

    private Document catalogManifest(RepositoryId repositoryId, Optional<String> catalogId) {
        Document manifest = catalogId.map(value -> findManifest(repositoryId, new GitEvidenceId(value))).orElseGet(() -> template
                .getCollection(IndexCollections.GIT_EVIDENCE_MANIFESTS).find(Filters.and(Filters.eq("repoId", repositoryId.value()),
                        Filters.eq("kind", "CATALOG"), Filters.eq("state", "READY"))).sort(Sorts.descending("observedAt")).maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS).first());
        Document ready = ready(manifest, "CATALOG");
        if (!GitEvidenceOwnership.standalone().equals(ownership(ready))) {
            throw new IndexContractMismatchException();
        }
        return ready;
    }

    private Document historyManifest(RepositoryId repositoryId, GitEvidenceId historyId) {
        Document manifest = ready(findManifest(repositoryId, historyId), "HISTORY");
        if (!GitEvidenceOwnership.standalone().equals(ownership(manifest))) {
            throw new IndexContractMismatchException();
        }
        return manifest;
    }

    private Document comparisonManifest(RepositoryId repositoryId, GitEvidenceId comparisonId) {
        Document manifest = ready(findManifest(repositoryId, comparisonId), "COMPARISON");
        String ownerJobId = requiredText(manifest, "ownerJobId");
        GitSnapshotId previousSnapshotId = new GitSnapshotId(requiredText(manifest, "previousSnapshotId"));
        GitSnapshotId currentSnapshotId = new GitSnapshotId(requiredText(manifest, "currentSnapshotId"));
        Optional<RepositoryRevision> previous = Optional.ofNullable(manifest.getString("previous")).map(RepositoryRevision::ofSha);
        RepositoryRevision current = new RepositoryRevision(requiredText(manifest, "current"));
        GitEvidenceOwnership ownership = ownership(manifest);
        if (previous.isEmpty() != "EMPTY_TREE".equals(requiredText(manifest, "ancestry"))) {
            throw new IndexContractMismatchException();
        }
        readySnapshot(repositoryId, previousSnapshotId, previous, ownerJobId, ownership);
        readySnapshot(repositoryId, currentSnapshotId, Optional.of(current), ownerJobId, ownership);
        authorizeReviewMembership(repositoryId, ownership, ownerJobId, new GitComparisonId(requiredText(manifest, "evidenceId")), previousSnapshotId,
                currentSnapshotId, previous, current);
        bindComparisonSources(repositoryId, manifest, previousSnapshotId, currentSnapshotId, previous, current, ownership);
        return manifest;
    }

    private Document readySnapshot(RepositoryId repositoryId, GitSnapshotId snapshotId, Optional<RepositoryRevision> revision, String ownerJobId,
                                   GitEvidenceOwnership ownership) {
        Document snapshot = ready(findManifest(repositoryId, new GitEvidenceId(snapshotId.value())), "SNAPSHOT");
        if (!Objects.equals(revision.map(RepositoryRevision::value).orElse(null), snapshot.getString("revision"))
                || !ownerJobId.equals(requiredText(snapshot, "ownerJobId")) || !ownership.equals(ownership(snapshot))) {
            throw new IndexContractMismatchException();
        }
        if (revision.isEmpty()) {
            if (snapshot.containsKey("sourceGenerationId") || requiredLong(snapshot, "total") != 0L) {
                throw new IndexContractMismatchException();
            }
        }
        return snapshot;
    }

    private GitEvidenceOwnership ownership(Document manifest) {
        Integer version = manifest.getInteger("gitEvidenceVersion");
        if (Objects.isNull(version) || version != IndexSchemaContract.GIT_EVIDENCE_VERSION) {
            throw new IndexContractMismatchException();
        }
        String scope = requiredText(manifest, "scope");
        if (GitPublicationScope.STANDALONE.name().equals(scope) && !manifest.containsKey("reviewId")) {
            return GitEvidenceOwnership.standalone();
        }
        if (GitPublicationScope.REVIEW.name().equals(scope)) {
            return new GitEvidenceOwnership(GitPublicationScope.REVIEW,
                    Optional.of(new ReviewId(requiredText(manifest, "reviewId"))));
        }
        throw new IndexContractMismatchException();
    }

    private void authorizeReviewMembership(RepositoryId repositoryId, GitEvidenceOwnership ownership, String ownerJobId,
                                           GitComparisonId comparisonId, GitSnapshotId previousSnapshotId, GitSnapshotId currentSnapshotId,
                                           Optional<RepositoryRevision> previous, RepositoryRevision current) {
        try {
            if (ownership.scope() == GitPublicationScope.REVIEW
                    && !ownerJobId.equals(reviews.requireReady(repositoryId, ownership.reviewId().orElseThrow()).ownerJobId())) {
                throw new IndexContractMismatchException();
            }
            reviews.requireGitMembership(repositoryId, ownership, comparisonId, previousSnapshotId, currentSnapshotId, previous, current);
        } catch (ReviewNotReadyException | ReviewFailedException exception) {
            throw new GitEvidenceNotReadyException();
        } catch (ReviewNotFoundException exception) {
            throw new IndexContractMismatchException();
        }
    }

    private static long comparisonTotal(Document manifest) {
        return requiredLong(manifest, "total");
    }

    private List<SemanticQueryContract.GitChangeItem> comparisonPage(RepositoryId repositoryId, Document manifest,
            String comparisonId, int offset, int limit, long total) {
        long expectedRows = pageRows(offset, limit, total);
        if (expectedRows == 0L) {
            return List.of();
        }
        List<SemanticQueryContract.GitChangeItem> changes = new ArrayList<>();
        long expectedOrdinal = offset;
        for (Document row : template.getCollection(IndexCollections.GIT_COMPARISON_CHANGES).find(Filters.and(Filters.eq("repoId", repositoryId.value()),
                Filters.eq("comparisonId", comparisonId), Filters.gte("ordinal", expectedOrdinal),
                Filters.lte("ordinal", expectedOrdinal + expectedRows - 1L))).sort(Sorts.ascending("ordinal")).limit(Math.toIntExact(expectedRows))
                .maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
            SemanticQueryContract.GitChangeItem change = change(row);
            if (requiredLong(row, "ordinal") != expectedOrdinal || !repositoryId.value().equals(requiredText(row, "repoId"))
                    || !comparisonId.equals(requiredText(row, "comparisonId"))) {
                throw new IndexContractMismatchException();
            }
            requireAllowedChange(repositoryId, manifest, change);
            changes.add(change);
            expectedOrdinal++;
        }
        if (changes.size() != Math.toIntExact(expectedRows)) {
            throw new IndexContractMismatchException();
        }
        return List.copyOf(changes);
    }

    private void requireAllowedChange(RepositoryId repositoryId, Document manifest, SemanticQueryContract.GitChangeItem change) {
        if (!change.oldPath().isEmpty()) {
            requireAllowedEndpoint(repositoryId, manifest, change.oldPath(), change.oldMode(), change.oldBlobId(),
                    "previousSnapshotId", "beforePolicy");
        }
        if (!change.newPath().isEmpty()) {
            requireAllowedEndpoint(repositoryId, manifest, change.newPath(), change.newMode(), change.newBlobId(),
                    "currentSnapshotId", "afterPolicy");
        }
    }

    private void requireAllowedEndpoint(RepositoryId repositoryId, Document comparison, String path, String mode,
            String blobId, String snapshotField, String policyField) {
        String snapshotId = requiredText(comparison, snapshotField);
        Document snapshot = ready(findManifest(repositoryId, new GitEvidenceId(snapshotId)), "SNAPSHOT");
        snapshot.put("_selectedPolicy", requiredPolicy(comparison, policyField));
        List<Document> matches = new ArrayList<>();
        for (Document row : template.getCollection(IndexCollections.GIT_SNAPSHOT_FILES).find(Filters.and(
                Filters.eq("repoId", repositoryId.value()), Filters.eq("snapshotId", snapshotId), Filters.eq("path", path)))
                .limit(2).maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
            matches.add(row);
        }
        if (matches.size() != 1) {
            throw new IndexContractMismatchException();
        }
        validatedFile(matches.getFirst(), snapshot);
        if (!mode.equals(requiredText(matches.getFirst(), "mode"))
                || !blobId.equals(requiredText(matches.getFirst(), "blobId"))) {
            throw new IndexContractMismatchException();
        }
    }

    private PatchPage patchPage(RepositoryId repositoryId, String comparisonId, String changeId, Document change, long ordinal, boolean hasCursor) {
        long expectedChunks = requiredLong(change, "patchChunkCount");
        String status = requiredText(change, "diffStatus");
        if (!"AVAILABLE".equals(status)) {
            if (hasCursor) {
                throw new IllegalArgumentException("diff cursor is invalid");
            }
            Document unexpected = template.getCollection(IndexCollections.GIT_COMPARISON_PATCHES).find(Filters.and(Filters.eq("repoId", repositoryId.value()),
                    Filters.eq("comparisonId", comparisonId), Filters.eq("changeId", changeId))).limit(1)
                    .maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS).first();
            if (expectedChunks != 0L || Objects.nonNull(unexpected)) {
                throw new IndexContractMismatchException();
            }
            return new PatchPage("", false);
        }
        if (expectedChunks == 0L) {
            throw new IndexContractMismatchException();
        }
        if (ordinal >= expectedChunks) {
            throw new IllegalArgumentException("diff cursor is invalid");
        }
        Document patch = template.getCollection(IndexCollections.GIT_COMPARISON_PATCHES).find(Filters.and(Filters.eq("repoId", repositoryId.value()),
                Filters.eq("comparisonId", comparisonId), Filters.eq("changeId", changeId), Filters.eq("ordinal", ordinal)))
                .maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS).first();
        if (Objects.isNull(patch)) {
            throw new IndexContractMismatchException();
        }
        if (requiredLong(patch, "ordinal") != ordinal || !repositoryId.value().equals(requiredText(patch, "repoId"))
                || !comparisonId.equals(requiredText(patch, "comparisonId")) || !changeId.equals(requiredText(patch, "changeId"))) {
            throw new IndexContractMismatchException();
        }
        String text = requiredString(patch, "patch");
        if (text.isEmpty() || text.getBytes(StandardCharsets.UTF_8).length > 64 * 1024) {
            throw new IndexContractMismatchException();
        }
        return new PatchPage(text, ordinal + 1L < expectedChunks);
    }

    private static long pageRows(int offset, int limit, long total) {
        long start = offset;
        if (start >= total) {
            return 0L;
        }
        return Math.min((long) limit, total - start);
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
            if (rawPath.length != 0 || !pathKey.isEmpty() || !"0".equals(mode) || !"0".repeat(40).equals(blobId)) {
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
        if ((kind == GitChangeKind.ADD && (oldPresent || !newPresent))
                || (kind == GitChangeKind.DELETE && (!oldPresent || newPresent))
                || (kind == GitChangeKind.MODIFY && (!oldPresent || !newPresent || !Arrays.equals(oldRawPath, newRawPath)
                || !oldMode.equals(newMode) || oldBlobId.equals(newBlobId)))
                || (kind == GitChangeKind.MODE && (!oldPresent || !newPresent || !Arrays.equals(oldRawPath, newRawPath) || oldMode.equals(newMode)))
                || (kind == GitChangeKind.RENAME && (!oldPresent || !newPresent || Arrays.equals(oldRawPath, newRawPath)))) {
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
        String payload = String.join("\u001f", DIFF_CURSOR_OPERATION, repositoryId.value(), comparisonId,
                Objects.requireNonNullElse(previous, ""), current, changeId, Long.toString(ordinal));
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
                    || !comparisonId.equals(values[2]) || !Objects.requireNonNullElse(previous, "").equals(values[3])
                    || !current.equals(values[4]) || !changeId.equals(values[5])) {
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
        int version = manifest.getInteger("gitEvidenceVersion", 0);
        if (version != IndexSchemaContract.GIT_EVIDENCE_VERSION) {
            throw new IndexContractMismatchException();
        }
        return manifest;
    }

    private static SemanticQueryContract.Page page(int offset, int limit, int returned, long total) {
        return new SemanticQueryContract.Page(offset, limit, returned, total, offset + returned < total);
    }
    private static Document requiredDocument(Document document, String field) {
        Document value = document.get(field, Document.class);
        if (Objects.isNull(value)) {
            throw new IndexContractMismatchException();
        }
        return value;
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
        Object value = document.get(field);
        if (!(value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte)) {
            throw new IndexContractMismatchException();
        }
        long resolved = ((Number) value).longValue();
        if (resolved < 0L) { throw new IndexContractMismatchException(); }
        return resolved;
    }
    private static Instant instant(Document document, String field) {
        java.util.Date value = document.getDate(field);
        if (Objects.isNull(value)) { throw new IndexContractMismatchException(); }
        return value.toInstant();
    }
}
