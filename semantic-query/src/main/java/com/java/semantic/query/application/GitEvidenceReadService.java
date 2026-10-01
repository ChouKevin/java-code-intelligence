package com.java.semantic.query.application;

import com.java.semantic.query.application.SelectedGenerationGuard.SourceContext;
import com.java.semantic.query.application.ReadContextSelector.AdmittedContext;
import com.java.semantic.query.application.ReadContextSelector.AdmittedComparison;
import com.java.semantic.query.application.SemanticQueryContract.*;
import com.java.semantic.model.codefact.CodeFactDetails;
import com.java.semantic.model.codefact.CodeFactId;
import com.java.semantic.model.codefact.SyntaxPosition;
import com.java.semantic.model.codefact.SyntaxRange;
import com.java.semantic.model.git.GitEvidenceId;
import com.java.semantic.model.git.GitChangeKind;
import com.java.semantic.model.git.GitEvidenceOwnership;
import com.java.semantic.model.git.GitPublicationScope;
import com.java.semantic.model.git.GitFileContentStatus;
import com.java.semantic.model.git.GitComparisonPolicyCoverage;
import com.java.semantic.model.index.IndexCollections;
import com.java.semantic.model.index.IndexSchemaContract;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.model.review.ReviewId;
import com.java.semantic.model.source.SourceContentKind;
import com.java.semantic.model.source.ProjectGuideState;
import com.java.semantic.query.config.ConfiguredReadPolicy;
import com.mongodb.MongoException;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.Binary;
import org.springframework.dao.DataAccessException;
import org.springframework.data.mongodb.core.MongoTemplate;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Bounded Mongo-only readers over evidence admitted once by the shared selector. */
public final class GitEvidenceReadService {
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;
    private static final int MAX_SEARCH_BYTES = 4 * 1024 * 1024;
    private final MongoTemplate template;
    private final ConfiguredReadPolicy readPolicy;
    private final Duration storageTimeout;
    private final CodeFactReadService facts;

    public GitEvidenceReadService(MongoTemplate template, ConfiguredReadPolicy readPolicy,
            Duration storageTimeout, CodeFactReadService facts) {
        this.template = Objects.requireNonNull(template);
        this.readPolicy = Objects.requireNonNull(readPolicy);
        this.storageTimeout = Objects.requireNonNull(storageTimeout);
        this.facts = Objects.requireNonNull(facts);
    }

    private <T> T storage(Supplier<T> action) {
        try { return action.get(); }
        catch (MongoException | DataAccessException exception) { throw new SemanticIndexUnavailableException(exception); }
    }

    public FileCollection listFiles(AdmittedContext admitted, FileListRequest request) {
        return storage(() -> {
            RepositoryId repo = repository(admitted);
            readPolicy.requireGitEvidenceVisible(repo);
            String snapshot = snapshot(admitted);
            String binding = QueryCursorCodec.binding("list_files", admitted, List.of(request.directory(),
                    request.nameFilter().orElse(""), request.pathFilter().orElse(""), Integer.toString(request.page().limit())));
            String prefix = request.directory().isEmpty() ? "" : request.directory() + "/";
            String lower = key(prefix);
            String upper = prefixEnd(lower);
            String seek = request.page().cursor().map(value -> QueryCursorCodec.decode(value, binding, 1).getFirst()).orElse(lower);
            if (!seek.matches("[0-9a-f]*") || seek.compareTo(lower) < 0 || (!upper.isEmpty() && seek.compareTo(upper) >= 0)) {
                throw new IllegalArgumentException("file cursor is outside the directory");
            }
            List<FileItem> items = new ArrayList<>();
            String continuation = seek;
            while (items.size() <= request.page().limit()) {
                List<Bson> scope = new ArrayList<>(List.of(Filters.eq("repoId", repo.value()),
                        Filters.eq("snapshotId", snapshot), Filters.gte("pathKey", seek)));
                if (!upper.isEmpty()) scope.add(Filters.lt("pathKey", upper));
                Document row = template.getCollection(IndexCollections.GIT_SNAPSHOT_FILES).find(Filters.and(scope))
                        .sort(Sorts.ascending("pathKey")).limit(1).maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS).first();
                if (Objects.isNull(row)) break;
                SnapshotFile file = validatedFile(row, admitted.source());
                String next = file.pathKey() + "00";
                String remaining = file.path().substring(prefix.length());
                int slash = remaining.indexOf('/');
                boolean matches = request.nameFilter().map(value -> basename(file.path()).contains(value)).orElse(true)
                        && request.pathFilter().map(file.path()::contains).orElse(true);
                if (matches) {
                    String path = slash < 0 ? file.path() : prefix + remaining.substring(0, slash);
                    if (items.size() == request.page().limit()) {
                        return new FileCollection(admitted.context(), items,
                                new Page(items.size(), true, Optional.of(QueryCursorCodec.encode(binding, List.of(continuation)))));
                    }
                    items.add(slash < 0 ? new FileItem(path, FileEntryType.FILE, Optional.of(file.kind()),
                            Optional.of(file.contentStatus()), Optional.of(file.byteLength()), guide(admitted.source(), file.kind()))
                            : new FileItem(path, FileEntryType.DIRECTORY, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()));
                    if (slash >= 0) next = prefixEnd(key(path + "/"));
                    continuation = next;
                }
                seek = next;
            }
            return new FileCollection(admitted.context(), items, new Page(items.size(), false, Optional.empty()));
        });
    }

    public TextSearchResult searchText(AdmittedContext admitted, TextSearchRequest request) {
        return storage(() -> {
            RepositoryId repo = repository(admitted);
            readPolicy.requireGitEvidenceVisible(repo);
            String snapshot = snapshot(admitted);
            String binding = QueryCursorCodec.binding("search_text", admitted,
                    List.of(request.query(), request.directory(), Integer.toString(request.page().limit())));
            SearchPosition position = request.page().cursor().map(value -> {
                List<String> fields = QueryCursorCodec.decode(value, binding, 3);
                return new SearchPosition(number(fields.get(0)), number(fields.get(1)), Math.toIntExact(number(fields.get(2))));
            }).orElse(SearchPosition.initial());
            List<TextMatch> matches = new ArrayList<>();
            SearchBudget budget = new SearchBudget();
            if (request.page().cursor().isPresent()) validateSearchCursorTarget(repo, snapshot, position, request.directory(), admitted.source());
            Bson scope = Filters.and(Filters.eq("repoId", repo.value()), Filters.eq("snapshotId", snapshot),
                    Filters.eq("contentStatus", "TEXT"), Filters.eq("contentKind", "CODE"),
                    Filters.gte("ordinal", position.fileOrdinal()), directoryFilter(request.directory()));
            for (Document row : template.getCollection(IndexCollections.GIT_SNAPSHOT_FILES).find(scope).sort(Sorts.ascending("ordinal"))
                    .batchSize(32).maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                SnapshotFile file = validatedFile(row, admitted.source());
                if (file.chunkCount() == 0L) continue;
                SearchStart start;
                try {
                    start = file.ordinal() == position.fileOrdinal()
                            ? decodeSearchPosition(repo, snapshot, file, position, budget)
                            : new SearchStart(firstPosition(repo, snapshot, file), Optional.empty());
                } catch (SearchBudgetExhaustedException exception) {
                    return searchResult(admitted, binding, matches, position);
                }
                SearchPage page = searchFile(repo, snapshot, file, start, request.query(), request.page().limit() - matches.size(), budget);
                matches.addAll(page.matches());
                if (!page.complete()) return searchResult(admitted, binding, matches,
                        new SearchPosition(file.ordinal(), page.position().chunkOrdinal(), page.position().byteOffset()));
                if (matches.size() == request.page().limit()) {
                    Optional<SnapshotFile> next = nextSearchFile(repo, snapshot, request.directory(), file.ordinal(), admitted.source());
                    if (next.isPresent()) return searchResult(admitted, binding, matches, new SearchPosition(next.orElseThrow().ordinal(), 0L, 0));
                    break;
                }
                position = new SearchPosition(file.ordinal() + 1L, 0L, 0);
            }
            return new TextSearchResult(admitted.context(), matches, new Page(matches.size(), false, Optional.empty()), true);
        });
    }

    private static TextSearchResult searchResult(AdmittedContext admitted, String binding, List<TextMatch> matches, SearchPosition position) {
        String cursor = QueryCursorCodec.encode(binding, List.of(Long.toString(position.fileOrdinal()),
                Long.toString(position.chunkOrdinal()), Integer.toString(position.byteOffset())));
        return new TextSearchResult(admitted.context(), matches, new Page(matches.size(), true, Optional.of(cursor)), false);
    }

    public SourceResult readSource(AdmittedContext admitted, SourceRequest request) {
        return storage(() -> {
            RepositoryId repo = repository(admitted);
            SourceContext source = admitted.source();
            String snapshot = snapshot(admitted);
            Optional<SyntaxRange> factRange = Optional.empty();
            String path;
            if (request.target().kind() == SourceTargetKind.FACT) {
                CodeFactDetails fact = facts.get(source, new CodeFactId(request.target().factId().orElseThrow()));
                path = fact.location().sourceFile();
                factRange = Optional.of(fact.location().range());
                facts.requireWholeSourceVisible(source, path);
            } else {
                readPolicy.requireGitEvidenceVisible(repo);
                path = request.target().path().orElseThrow();
            }
            SnapshotFile file = file(repo, snapshot, path, source);
            if (factRange.isPresent() && file.kind() != SourceContentKind.CODE) throw new IndexContractMismatchException();
            if (!"TEXT".equals(file.contentStatus())) {
                if (request.cursor().isPresent()) throw new IllegalArgumentException("unavailable source has no continuation");
                return new SourceResult(admitted.context(), request.target(), path, file.kind(), file.contentStatus(), Optional.empty(),
                        factRange, Optional.empty(), Optional.empty(), true, true, true, Optional.empty(), guide(source, file.kind()));
            }
            ReadPosition start;
            Optional<ReadPosition> end = Optional.empty();
            if (factRange.isPresent()) {
                SyntaxRange range = factRange.orElseThrow();
                ReadPosition exactStart = coordinate(repo, snapshot, file, range.start(), false);
                ReadPosition exactEnd = coordinate(repo, snapshot, file, range.end(), false);
                int context = request.target().contextLines().orElseThrow();
                start = context == 0 ? exactStart : coordinate(repo, snapshot, file,
                        new SyntaxPosition(Math.max(0, range.start().line() - context), 0), false);
                int lastLine = range.end().character() == 0 && range.end().line() > range.start().line()
                        ? range.end().line() - 1 : range.end().line();
                end = Optional.of(context == 0 ? exactEnd : coordinate(repo, snapshot, file,
                        new SyntaxPosition(lastLine + context + 1, 0), true));
            } else {
                SyntaxPosition requestedStart = new SyntaxPosition(request.target().startLine().orElseThrow() - 1, 0);
                start = coordinate(repo, snapshot, file, requestedStart, true);
                if (!syntax(start).equals(requestedStart)) throw new IllegalArgumentException("start line is outside the file");
            }
            SourceWindow window = new SourceWindow(syntax(start), end.map(GitEvidenceReadService::syntax));
            String binding = QueryCursorCodec.binding("read_source", admitted, List.of(request.target().kind().name(), path,
                    request.target().factId().orElse(""), request.target().startLine().map(Object::toString).orElse(""),
                    request.target().contextLines().map(Object::toString).orElse(""), Integer.toString(request.maxLines()),
                    window.toString(), factRange.map(Object::toString).orElse(""), file.checksum()));
            ReadPosition pageStart = start;
            if (request.cursor().isPresent()) {
                List<String> fields = QueryCursorCodec.decode(request.cursor().orElseThrow(), binding, 2);
                pageStart = readPosition(repo, snapshot, file, number(fields.get(0)), Math.toIntExact(number(fields.get(1))));
                if (pageStart.byteOffset() <= start.byteOffset() || pageStart.byteOffset() >= end.map(ReadPosition::byteOffset).orElse(Math.toIntExact(file.byteLength()))) {
                    throw new IllegalArgumentException("source cursor is outside its window");
                }
            }
            ReadPage page = readPage(repo, snapshot, file, pageStart, request.maxLines(), end);
            Optional<String> next = page.hasMore() ? Optional.of(QueryCursorCodec.encode(binding,
                    List.of(Long.toString(page.position().chunkOrdinal()), Integer.toString(page.position().byteOffset())))) : Optional.empty();
            return new SourceResult(admitted.context(), request.target(), path, file.kind(), "TEXT", Optional.of(page.content()), factRange,
                    Optional.of(window), Optional.of(new SyntaxRange(syntax(pageStart), syntax(page.position()))), !page.hasMore(),
                    pageStart.column() == 1, page.endLineComplete() || page.position().byteOffset() == file.byteLength(), next, guide(source, file.kind()));
        });
    }

    private ReadPosition coordinate(RepositoryId repo, String snapshot, SnapshotFile file, SyntaxPosition wanted, boolean clipEof) {
        if (file.chunkCount() == 0L) {
            if (!clipEof && (wanted.line() != 0 || wanted.character() != 0)) throw new IndexContractMismatchException();
            return firstPosition(repo, snapshot, file);
        }
        Document row = template.getCollection(IndexCollections.GIT_SNAPSHOT_CHUNKS).find(Filters.and(
                Filters.eq("repoId", repo.value()), Filters.eq("snapshotId", snapshot), Filters.eq("pathKey", file.pathKey()),
                Filters.or(Filters.lt("line", wanted.line() + 1), Filters.and(Filters.eq("line", wanted.line() + 1),
                        Filters.lte("column", wanted.character() + 1)))))
                .sort(Sorts.descending("ordinal")).limit(1).maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS).first();
        if (Objects.isNull(row)) throw new IndexContractMismatchException();
        ReadPosition position = checkpoint(repo, snapshot, file, requiredLong(row, "ordinal"));
        while (position.byteOffset() < file.byteLength()) {
            ChunkData chunk = chunkData(repo, snapshot, file, position.chunkOrdinal(), position.byteOffset(), position.line(), position.column());
            int index = charIndexAtByteOffset(chunk.text(), position.byteOffset() - chunk.byteOffset());
            while (index < chunk.text().length()) {
                if (position.line() - 1 == wanted.line() && position.column() - 1 == wanted.character()) return position;
                int cp = chunk.text().codePointAt(index);
                if ((position.line() == wanted.line() + 1 && (cp == '\r' || cp == '\n'))
                        || position.line() - 1 > wanted.line()
                        || (position.line() - 1 == wanted.line() && position.column() - 1 > wanted.character())) {
                    throw new IndexContractMismatchException();
                }
                position = advance(position, cp);
                index += Character.charCount(cp);
            }
            if (position.byteOffset() < file.byteLength()) position = nextChunkPosition(repo, snapshot, file, position, chunk);
        }
        if (!clipEof && !syntax(position).equals(wanted)) throw new IndexContractMismatchException();
        return position;
    }
    private static SyntaxPosition syntax(ReadPosition position) { return new SyntaxPosition(position.line() - 1, position.column() - 1); }
    private static RepositoryId repository(AdmittedContext context) { return context.source().selected().repositoryId(); }
    private static String snapshot(AdmittedContext context) { return context.source().snapshot().snapshotId().value(); }
    private static String basename(String path) { return path.substring(path.lastIndexOf('/') + 1); }
    private static String key(String path) { return java.util.HexFormat.of().formatHex(path.getBytes(StandardCharsets.UTF_8)); }
    private static String prefixEnd(String key) {
        if (key.isEmpty()) return "";
        for (int index = key.length() - 1; index >= 0; index--) {
            int digit = Character.digit(key.charAt(index), 16);
            if (digit < 15) return key.substring(0, index) + Character.forDigit(digit + 1, 16);
        }
        throw new IllegalArgumentException("invalid path prefix");
    }
    private static Bson directoryFilter(String directory) {
        if (directory.isEmpty()) return new Document();
        String prefix = key(directory + "/");
        return Filters.and(Filters.gte("pathKey", prefix), Filters.lt("pathKey", prefixEnd(prefix)));
    }
    private static Optional<GuideInfo> guide(SourceContext source, SourceContentKind kind) {
        return kind == SourceContentKind.PROJECT_GUIDE ? Optional.of(GuideInfo.from(source.guide())) : Optional.empty();
    }
    private static long number(String value) {
        try {
            long result = Long.parseLong(value);
            if (result < 0) throw new IllegalArgumentException("negative cursor position");
            return result;
        } catch (NumberFormatException exception) { throw new IllegalArgumentException("invalid cursor position", exception); }
    }

    private SnapshotFile file(RepositoryId repo, String snapshot, String path, SourceContext source) {
        Document row = template.getCollection(IndexCollections.GIT_SNAPSHOT_FILES).find(Filters.and(
                Filters.eq("repoId", repo.value()), Filters.eq("snapshotId", snapshot), Filters.eq("pathKey", key(path))))
                .maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS).first();
        if (Objects.isNull(row)) throw new GitEvidenceNotFoundException();
        SnapshotFile file = validatedFile(row, source);
        if (!path.equals(file.path())) throw new IndexContractMismatchException();
        return file;
    }
    private SnapshotFile validatedFile(Document row, SourceContext source) {
        String path = requiredText(row, "path");
        byte[] rawPath = requiredBytes(row, "rawPath");
        String pathKey = requiredText(row, "pathKey");
        long ordinal = requiredLong(row, "ordinal");
        long length = requiredLong(row, "byteLength");
        long chunks = requiredLong(row, "chunkCount");
        String status = requiredText(row, "contentStatus");
        String checksum = requiredText(row, "checksum");
        SourceContentKind kind = enumValue(SourceContentKind.class, requiredText(row, "contentKind"));
        enumValue(GitFileContentStatus.class, status);
        if (!source.selected().repositoryId().value().equals(requiredText(row, "repoId"))
                || !source.snapshot().snapshotId().value().equals(requiredText(row, "snapshotId"))
                || !source.fingerprint().equals(requiredText(row, "policyFingerprint"))
                || !List.of("100644", "100755").contains(requiredText(row, "mode"))
                || !requiredText(row, "blobId").matches("[0-9a-f]{40}") || !checksum.matches("[0-9a-f]{64}")
                || !pathKey.equals(java.util.HexFormat.of().formatHex(rawPath)) || !path.equals(displayPath(rawPath))
                || ordinal >= source.evidence().total() || ("TEXT".equals(status) && length > source.evidence().fileTextBytesLimit())
                || ("TEXT".equals(status) && ((length == 0) != (chunks == 0))) || (!"TEXT".equals(status) && chunks != 0)) {
            throw new IndexContractMismatchException();
        }
        if (kind == SourceContentKind.CODE && source.policy().allowsCode(path)) {
            return new SnapshotFile(path, pathKey, ordinal, length, chunks, status, kind, checksum);
        }
        if (kind == SourceContentKind.PROJECT_GUIDE && source.policy().allowsGuide(path)
                && source.guide().state() == ProjectGuideState.AVAILABLE && "TEXT".equals(status)
                && source.guide().path().filter(path::equals).isPresent()
                && source.guide().digest().filter(checksum::equals).isPresent()
                && source.guide().importedRevision().filter(source.selected().revision()::equals).isPresent()) {
            return new SnapshotFile(path, pathKey, ordinal, length, chunks, status, kind, checksum);
        }
        throw new IndexContractMismatchException();
    }
    private record SnapshotFile(String path, String pathKey, long ordinal, long byteLength, long chunkCount,
            String contentStatus, SourceContentKind kind, String checksum) { }

    public GitBranchCollection listGitBranches(GitBranchRequest request) {
        return storage(() -> {
            RepositoryId repo = new RepositoryId(request.repositoryId());
            readPolicy.requireGitEvidenceVisible(repo);
            MetadataPage selected = metadata(repo, Optional.empty(), request.page(), "list_git_branches");
            List<GitBranchItem> items = new ArrayList<>();
            for (Document row : metadataRows(repo, selected, IndexCollections.GIT_BRANCHES, "catalogId", selected.catalogTotal(), request.page().limit())) {
                items.add(new GitBranchItem(requiredText(row, "branch"), sha(requiredText(row, "head"))));
            }
            return new GitBranchCollection(repo.value(), selected.identity(), items,
                    metadataPage(selected, items.size(), selected.catalogTotal(), request.page().limit()));
        });
    }
    public GitCommitCollection listGitCommits(GitCommitRequest request) {
        return storage(() -> {
            RepositoryId repo = new RepositoryId(request.repositoryId());
            readPolicy.requireGitEvidenceVisible(repo);
            MetadataPage selected = metadata(repo, Optional.of(request.branch()), request.page(), "list_git_commits");
            List<GitCommitItem> items = new ArrayList<>();
            for (Document row : metadataRows(repo, selected, IndexCollections.GIT_COMMITS, "historyId", selected.historyTotal(), request.page().limit())) {
                String revision = sha(requiredText(row, "revision"));
                List<String> parents = row.getList("parents", String.class);
                if (Objects.isNull(parents)) throw new IndexContractMismatchException();
                parents.forEach(GitEvidenceReadService::sha);
                items.add(new GitCommitItem(revision, revision.substring(0, 7), parents, requiredString(row, "subject"), instant(row, "committedAt")));
            }
            return new GitCommitCollection(repo.value(), selected.identity(), items,
                    metadataPage(selected, items.size(), selected.historyTotal(), request.page().limit()));
        });
    }
    private MetadataPage metadata(RepositoryId repo, Optional<String> branch, PageRequest page, String operation) {
        String requestBinding = QueryCursorCodec.binding(operation, List.of(repo.value(), branch.orElse(""), Integer.toString(page.limit())));
        Document job;
        long ordinal = 0;
        List<String> pinned = List.of();
        if (page.cursor().isPresent()) {
            pinned = QueryCursorCodec.decode(page.cursor().orElseThrow(), requestBinding, 8);
            ordinal = number(pinned.get(7));
            job = template.getCollection(IndexCollections.INDEX_JOBS).find(Filters.and(Filters.eq("repoId", repo.value()),
                    Filters.eq("jobId", pinned.getFirst()))).maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS).first();
        } else {
            job = null;
            if (branch.isEmpty()) {
                Document repository = template.getCollection(IndexCollections.REPOSITORIES).find(Filters.eq("repoId", repo.value()))
                        .maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS).first();
                if (Objects.isNull(repository)) throw new RepositoryNotFoundException();
                Document pointer = repository.get("metadataPointer", Document.class);
                if (Objects.isNull(pointer)) throw new MetadataNotPreparedException();
                if (Objects.nonNull(pointer)) {
                    Document catalog = findManifest(repo, new GitEvidenceId(requiredText(pointer, "catalogId")));
                    if (Objects.isNull(catalog)) throw new IndexContractMismatchException();
                    Document candidate = template.getCollection(IndexCollections.INDEX_JOBS).find(Filters.and(Filters.eq("repoId", repo.value()),
                            Filters.eq("jobId", requiredText(catalog, "ownerJobId"))))
                            .maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS).first();
                    if (Objects.isNull(candidate)) throw new IndexContractMismatchException();
                    if ("COMPLETE".equals(candidate.getString("phase"))) {
                        MetadataPage admitted = admitMetadata(repo, candidate, requestBinding, 0);
                        MetadataIdentity identity = admitted.identity();
                        if (!identity.catalogId().equals(requiredText(pointer, "catalogId"))
                                || !identity.historyId().equals(requiredText(pointer, "historyId"))
                                || !identity.branch().equals(requiredText(pointer, "branch"))
                                || !identity.headRevision().equals(requiredText(pointer, "headRevision"))
                                || !identity.observedAt().equals(instant(pointer, "observedAt"))) throw new IndexContractMismatchException();
                        return admitted;
                    }
                    if (!"GIT_METADATA".equals(requiredText(candidate, "operation"))
                            || !"RUNNING".equals(requiredText(candidate, "phase"))
                            || !Boolean.TRUE.equals(candidate.getBoolean("active"))
                            || requiredLong(candidate, "jobVersion") != IndexSchemaContract.PERSISTED_JOB_VERSION
                            || !GitEvidenceOwnership.standalone().equals(ownership(ready(catalog, "CATALOG")))) {
                        throw new IndexContractMismatchException();
                    }
                    Document pending = requiredDocument(requiredDocument(candidate, "gitEvidence"), "metadataResult");
                    if (!requiredText(pointer, "catalogId").equals(requiredText(pending, "catalogId"))
                            || !requiredText(pointer, "historyId").equals(requiredText(pending, "historyId"))
                            || !requiredText(pointer, "branch").equals(requiredText(pending, "branch"))
                            || !requiredText(pointer, "headRevision").equals(requiredText(pending, "revision"))
                            || !instant(pointer, "observedAt").equals(instant(pending, "preparedAt"))) {
                        throw new IndexContractMismatchException();
                    }
                }
            }
            List<Bson> filters = new ArrayList<>(List.of(Filters.eq("repoId", repo.value()), Filters.eq("operation", "GIT_METADATA"), Filters.eq("phase", "COMPLETE")));
            branch.ifPresent(value -> filters.add(Filters.eq("gitEvidence.branch", value)));
            job = template.getCollection(IndexCollections.INDEX_JOBS).find(Filters.and(filters))
                    .sort(Sorts.orderBy(Sorts.descending("createdAt"), Sorts.descending("jobId"))).limit(1)
                    .maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS).first();
        }
        if (Objects.isNull(job)) throw new MetadataNotPreparedException();
        MetadataPage admitted = admitMetadata(repo, job, requestBinding, ordinal);
        if (branch.filter(value -> !value.equals(admitted.identity().branch())).isPresent()) throw new IndexContractMismatchException();
        if (!pinned.isEmpty() && !metadataPositions(admitted, ordinal).equals(pinned)) throw new IllegalArgumentException("metadata cursor identity changed");
        long total = branch.isPresent() ? admitted.historyTotal() : admitted.catalogTotal();
        if (page.cursor().isPresent() && (ordinal == 0 || ordinal >= total)) throw new IllegalArgumentException("metadata cursor is outside evidence");
        return admitted;
    }
    private MetadataPage admitMetadata(RepositoryId repo, Document job, String binding, long ordinal) {
        if (!repo.value().equals(requiredText(job, "repoId")) || !"GIT_METADATA".equals(requiredText(job, "operation"))
                || !"COMPLETE".equals(requiredText(job, "phase")) || !Boolean.FALSE.equals(job.getBoolean("active"))
                || requiredLong(job, "jobVersion") != IndexSchemaContract.PERSISTED_JOB_VERSION) throw new IndexContractMismatchException();
        Document payload = requiredDocument(job, "gitEvidence");
        Document result = requiredDocument(payload, "metadataResult");
        String catalogId = new GitEvidenceId(requiredText(result, "catalogId")).value();
        String historyId = new GitEvidenceId(requiredText(result, "historyId")).value();
        String branch = requiredText(result, "branch");
        String revision = sha(requiredText(result, "revision"));
        String owner = requiredText(job, "jobId");
        Instant observed = instant(result, "preparedAt");
        Document catalog = ready(findManifest(repo, new GitEvidenceId(catalogId)), "CATALOG");
        Document history = ready(findManifest(repo, new GitEvidenceId(historyId)), "HISTORY");
        long catalogTotal = requiredLong(catalog, "total");
        long historyTotal = requiredLong(history, "total");
        if (!repo.value().equals(requiredText(result, "repoId")) || !"READY".equals(requiredText(result, "state"))
                || !"STANDALONE".equals(requiredText(result, "publicationScope"))
                || requiredLong(result, "gitEvidenceVersion") != IndexSchemaContract.GIT_EVIDENCE_VERSION
                || historyTotal < 1 || requiredLong(result, "total") != historyTotal
                || !catalogId.equals(requiredText(payload, "catalogId")) || !historyId.equals(requiredText(payload, "evidenceId"))
                || !branch.equals(requiredText(payload, "branch")) || !revision.equals(requiredText(payload, "revision"))
                || !catalogId.equals(requiredText(history, "catalogId")) || !branch.equals(requiredText(history, "branch"))
                || !revision.equals(requiredText(history, "revision")) || !observed.equals(instant(catalog, "observedAt"))
                || !observed.equals(instant(history, "preparedAt"))) throw new IndexContractMismatchException();
        for (Document manifest : List.of(catalog, history)) {
            if (!repo.value().equals(requiredText(manifest, "repoId")) || !owner.equals(requiredText(manifest, "ownerJobId"))
                    || !GitEvidenceOwnership.standalone().equals(ownership(manifest))
                    || !requiredText(manifest, "contentDigest").matches("[0-9a-f]{64}")) throw new IndexContractMismatchException();
        }
        Document head = template.getCollection(IndexCollections.GIT_BRANCHES).find(Filters.and(Filters.eq("repoId", repo.value()),
                Filters.eq("catalogId", catalogId), Filters.eq("branch", branch))).maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS).first();
        if (Objects.isNull(head) || !revision.equals(requiredText(head, "head")) || requiredLong(head, "ordinal") >= catalogTotal) {
            throw new IndexContractMismatchException();
        }
        return new MetadataPage(new MetadataIdentity(owner, catalogId, historyId, branch, revision, observed),
                catalogTotal, historyTotal, requiredText(catalog, "contentDigest"), requiredText(history, "contentDigest"), binding, ordinal);
    }
    private List<Document> metadataRows(RepositoryId repo, MetadataPage selected, String collection, String field, long total, int limit) {
        String id = field.equals("catalogId") ? selected.identity().catalogId() : selected.identity().historyId();
        long count = Math.min(limit, total - selected.ordinal());
        List<Document> rows = new ArrayList<>();
        for (Document row : template.getCollection(collection).find(Filters.and(Filters.eq("repoId", repo.value()), Filters.eq(field, id),
                Filters.gte("ordinal", selected.ordinal()), Filters.lt("ordinal", selected.ordinal() + count))).sort(Sorts.ascending("ordinal"))
                .limit(limit).maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
            if (!repo.value().equals(requiredText(row, "repoId")) || !id.equals(requiredText(row, field))
                    || requiredLong(row, "ordinal") != selected.ordinal() + rows.size()) throw new IndexContractMismatchException();
            rows.add(row);
        }
        if (rows.size() != count) throw new IndexContractMismatchException();
        return rows;
    }
    private static Page metadataPage(MetadataPage selected, int returned, long total, int limit) {
        long next = selected.ordinal() + returned;
        return new Page(returned, next < total, next < total ? Optional.of(QueryCursorCodec.encode(selected.binding(), metadataPositions(selected, next))) : Optional.empty());
    }
    private static List<String> metadataPositions(MetadataPage selected, long ordinal) {
        MetadataIdentity id = selected.identity();
        return List.of(id.jobId(), id.catalogId(), id.historyId(), id.branch(), id.headRevision(), id.observedAt().toString(),
                QueryCursorCodec.binding("metadata_pair", List.of(selected.catalogDigest(), selected.historyDigest(),
                        Long.toString(selected.catalogTotal()), Long.toString(selected.historyTotal()))), Long.toString(ordinal));
    }
    private record MetadataPage(MetadataIdentity identity, long catalogTotal, long historyTotal,
            String catalogDigest, String historyDigest, String binding, long ordinal) { }
    private static String sha(String value) {
        try { return RepositoryRevision.ofSha(value).value(); }
        catch (IllegalArgumentException exception) { throw new IndexContractMismatchException(); }
    }
    private static GitEvidenceOwnership ownership(Document manifest) {
        if (requiredLong(manifest, "gitEvidenceVersion") != IndexSchemaContract.GIT_EVIDENCE_VERSION) throw new IndexContractMismatchException();
        try {
            return new GitEvidenceOwnership(GitPublicationScope.valueOf(requiredText(manifest, "scope")),
                    Optional.ofNullable(manifest.getString("reviewId")).map(ReviewId::new));
        } catch (IllegalArgumentException exception) { throw new IndexContractMismatchException(); }
    }

    public ComparisonResult compareRevisions(AdmittedComparison admitted, ComparisonRequest request) {
        return storage(() -> {
            ComparisonEvidence evidence = comparison(admitted);
            Document manifest = evidence.manifest();
            String id = requiredText(manifest, "evidenceId");
            String binding = comparisonBinding("compare_revisions", admitted, evidence, List.of(Integer.toString(request.page().limit())));
            long ordinal = request.page().cursor().map(value -> number(QueryCursorCodec.decode(value, binding, 1).getFirst())).orElse(0L);
            long total = requiredLong(manifest, "total");
            if (request.page().cursor().isPresent() && (ordinal == 0 || ordinal >= total)) throw new IllegalArgumentException("comparison cursor is invalid");
            long count = Math.min(request.page().limit(), total - ordinal);
            List<GitChangeItem> items = new ArrayList<>();
            for (Document row : template.getCollection(IndexCollections.GIT_COMPARISON_CHANGES).find(Filters.and(
                    Filters.eq("repoId", admitted.comparisonContext().repositoryId()), Filters.eq("comparisonId", id),
                    Filters.gte("ordinal", ordinal), Filters.lt("ordinal", ordinal + count))).sort(Sorts.ascending("ordinal"))
                    .limit(request.page().limit()).maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                if (requiredLong(row, "ordinal") != ordinal + items.size()) throw new IndexContractMismatchException();
                items.add(change(admitted, row, id));
            }
            if (items.size() != count) throw new IndexContractMismatchException();
            long next = ordinal + items.size();
            return new ComparisonResult(admitted.comparisonContext(), requiredText(manifest, "ancestry"), evidence.coverage(), items,
                    new Page(items.size(), next < total, next < total
                            ? Optional.of(QueryCursorCodec.encode(binding, List.of(Long.toString(next)))) : Optional.empty()));
        });
    }
    public FileDiffResult getFileDiff(AdmittedComparison admitted, FileDiffRequest request) {
        return storage(() -> {
            ComparisonEvidence evidence = comparison(admitted);
            Document manifest = evidence.manifest();
            String id = requiredText(manifest, "evidenceId");
            RepositoryId repo = new RepositoryId(admitted.comparisonContext().repositoryId());
            Document row = template.getCollection(IndexCollections.GIT_COMPARISON_CHANGES).find(Filters.and(Filters.eq("repoId", repo.value()),
                    Filters.eq("comparisonId", id), Filters.eq("changeId", request.changeId())))
                    .maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS).first();
            if (Objects.isNull(row)) throw new GitEvidenceNotFoundException();
            if (requiredLong(row, "ordinal") >= requiredLong(manifest, "total")) throw new IndexContractMismatchException();
            GitChangeItem change = change(admitted, row, id);
            String binding = comparisonBinding("get_file_diff", admitted, evidence, List.of(request.changeId(), change.toString(),
                    Long.toString(requiredLong(row, "patchChunkCount"))));
            long ordinal = request.cursor().map(value -> number(QueryCursorCodec.decode(value, binding, 1).getFirst())).orElse(0L);
            if (request.cursor().isPresent() && ordinal == 0) throw new IllegalArgumentException("diff cursor is invalid");
            PatchPage page = patchPage(repo, id, request.changeId(), row, ordinal, request.cursor().isPresent());
            return new FileDiffResult(admitted.comparisonContext(), change,
                    "AVAILABLE".equals(change.diffStatus()) ? Optional.of(page.text()) : Optional.empty(), !page.hasNext(),
                    page.hasNext() ? Optional.of(QueryCursorCodec.encode(binding, List.of(Long.toString(ordinal + 1)))) : Optional.empty());
        });
    }
    private record ComparisonEvidence(Document manifest, GitComparisonPolicyCoverage coverage) { }
    private ComparisonEvidence comparison(AdmittedComparison admitted) {
        RepositoryId repo = new RepositoryId(admitted.comparisonContext().repositoryId());
        readPolicy.requireGitEvidenceVisible(repo);
        String id = admitted.manifest().comparisonId().orElseThrow(IndexContractMismatchException::new).value();
        Document manifest = ready(findManifest(repo, new GitEvidenceId(id)), "COMPARISON");
        GitEvidenceOwnership owner = new GitEvidenceOwnership(GitPublicationScope.REVIEW, Optional.of(admitted.manifest().reviewId()));
        if (!owner.equals(ownership(manifest)) || !admitted.manifest().ownerJobId().equals(requiredText(manifest, "ownerJobId"))
                || !repo.value().equals(requiredText(manifest, "repoId")) || !id.equals(requiredText(manifest, "evidenceId"))
                || !admitted.comparisonContext().after().revision().orElseThrow().equals(requiredText(manifest, "current"))
                || !Objects.equals(admitted.comparisonContext().before().revision().orElse(null), manifest.getString("previous"))
                || !snapshot(admitted.after()).equals(requiredText(manifest, "currentSnapshotId"))
                || !requiredText(manifest, "contentDigest").matches("[0-9a-f]{64}")) throw new IndexContractMismatchException();
        if (admitted.before().isPresent()) {
            if (!snapshot(admitted.before().orElseThrow()).equals(requiredText(manifest, "previousSnapshotId"))) throw new IndexContractMismatchException();
        } else {
            Document empty = ready(findManifest(repo, new GitEvidenceId(requiredText(manifest, "previousSnapshotId"))), "SNAPSHOT");
            if (!"EMPTY_TREE".equals(requiredText(manifest, "ancestry")) || !owner.equals(ownership(empty))
                    || !admitted.manifest().ownerJobId().equals(requiredText(empty, "ownerJobId")) || empty.containsKey("revision")
                    || empty.containsKey("sourceGenerationId") || requiredLong(empty, "total") != 0) throw new IndexContractMismatchException();
        }
        return new ComparisonEvidence(manifest, coverage(manifest));
    }
    private static GitComparisonPolicyCoverage coverage(Document manifest) {
        try {
            return GitComparisonPolicyCoverage.fromFields(manifest.get("policyCoverage", Document.class));
        } catch (IllegalArgumentException | ArithmeticException | ClassCastException exception) {
            throw new IndexContractMismatchException();
        }
    }
    private static String comparisonBinding(String operation, AdmittedComparison admitted, ComparisonEvidence evidence, List<String> filters) {
        Document manifest = evidence.manifest();
        List<String> fields = new ArrayList<>(List.of(admitted.comparisonContext().toString(), admitted.manifest().ownerJobId(),
                requiredText(manifest, "evidenceId"), requiredText(manifest, "contentDigest"), Long.toString(requiredLong(manifest, "total")),
                QueryCursorCodec.binding(operation, admitted.after(), List.of()),
                admitted.before().map(value -> QueryCursorCodec.binding(operation, value, List.of())).orElse("EMPTY_TREE")));
        fields.add(evidence.coverage().toString());
        fields.addAll(filters);
        return QueryCursorCodec.binding(operation, fields);
    }
    private GitChangeItem change(AdmittedComparison admitted, Document row, String id) {
        if (!admitted.comparisonContext().repositoryId().equals(requiredText(row, "repoId")) || !id.equals(requiredText(row, "comparisonId"))) {
            throw new IndexContractMismatchException();
        }
        String kindText = requiredText(row, "kind");
        GitChangeKind kind = enumValue(GitChangeKind.class, kindText);
        String oldPath = requiredString(row, "oldPath");
        String newPath = requiredString(row, "newPath");
        byte[] oldRaw = requiredBytes(row, "oldRawPath");
        byte[] newRaw = requiredBytes(row, "newRawPath");
        String oldMode = requiredString(row, "oldMode");
        String newMode = requiredString(row, "newMode");
        String oldBlob = requiredString(row, "oldBlobId");
        String newBlob = requiredString(row, "newBlobId");
        String status = requiredText(row, "diffStatus");
        validateStatus(status);
        validateEndpoint(oldPath, oldRaw, requiredString(row, "oldPathKey"), oldMode, oldBlob);
        validateEndpoint(newPath, newRaw, requiredString(row, "newPathKey"), newMode, newBlob);
        validateChangeCombination(kind, oldPath, oldRaw, oldMode, oldBlob, newPath, newRaw, newMode, newBlob);
        validatePatchMetadata(status, requiredLong(row, "patchChunkCount"));
        Optional<ChangeEndpoint> before = oldPath.isEmpty() ? Optional.empty() : Optional.of(endpoint(
                admitted.before().orElseThrow(IndexContractMismatchException::new), oldPath, oldMode, oldBlob));
        Optional<ChangeEndpoint> after = newPath.isEmpty() ? Optional.empty() : Optional.of(endpoint(admitted.after(), newPath, newMode, newBlob));
        return new GitChangeItem(requiredText(row, "changeId"), kind.name(), before, after, status);
    }
    private ChangeEndpoint endpoint(AdmittedContext admitted, String path, String mode, String blob) {
        RepositoryId repo = repository(admitted);
        Document row = template.getCollection(IndexCollections.GIT_SNAPSHOT_FILES).find(Filters.and(Filters.eq("repoId", repo.value()),
                Filters.eq("snapshotId", snapshot(admitted)), Filters.eq("pathKey", key(path))))
                .maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS).first();
        if (Objects.isNull(row)) throw new IndexContractMismatchException();
        SnapshotFile file = validatedFile(row, admitted.source());
        if (!mode.equals(requiredText(row, "mode")) || !blob.equals(requiredText(row, "blobId"))) throw new IndexContractMismatchException();
        return new ChangeEndpoint(path, mode, blob, file.kind(), guide(admitted.source(), file.kind()));
    }
    private ReadPosition firstPosition(RepositoryId repositoryId, String snapshotId, SnapshotFile file) {
        if (file.chunkCount() == 0L) {
            return new ReadPosition(0L, 0, 1, 1, true);
        }
        return checkpoint(repositoryId, snapshotId, file, 0L);
    }

    private ReadPage readPage(RepositoryId repositoryId, String snapshotId, SnapshotFile file, ReadPosition start, int maxLines, Optional<ReadPosition> fence) {
        StringBuilder content = new StringBuilder();
        ReadPosition position = start;
        int responseBytes = 0;
        int returnedLines = 0;
        int endOffset = fence.map(ReadPosition::byteOffset).orElse(Math.toIntExact(file.byteLength()));
        if (position.byteOffset() == endOffset) return page(content, start, position, false);
        while (position.chunkOrdinal() < file.chunkCount()) {
            ChunkData chunk = chunkData(repositoryId, snapshotId, file, position.chunkOrdinal(), position.byteOffset(), position.line(), position.column());
            int character = charIndexAtByteOffset(chunk.text(), Math.toIntExact(position.byteOffset() - chunk.byteOffset()));
            while (character < chunk.text().length()) {
                if (position.byteOffset() == endOffset) return page(content, start, position, false);
                int codePoint = chunk.text().codePointAt(character);
                int bytes = utf8Bytes(codePoint);
                int reservedBytes = bytes;
                if (codePoint == '\r' && character + 1 < chunk.text().length() && chunk.text().charAt(character + 1) == '\n') reservedBytes++;
                if (responseBytes + reservedBytes > MAX_RESPONSE_BYTES) {
                    return page(content, start, position, true);
                }
                content.appendCodePoint(codePoint);
                responseBytes += bytes;
                position = advance(position, codePoint);
                character += Character.charCount(codePoint);
                if (codePoint == '\n' && ++returnedLines == maxLines) {
                    boolean hasMore = position.byteOffset() < endOffset;
                    if (hasMore && character == chunk.text().length()) {
                        position = nextChunkPosition(repositoryId, snapshotId, file, position, chunk);
                    }
                    return page(content, start, position, hasMore);
                }
            }
            if (position.byteOffset() == endOffset) {
                return page(content, start, position, false);
            }
            position = nextChunkPosition(repositoryId, snapshotId, file, position, chunk);
        }
        return page(content, start, position, false);
    }

    private static ReadPage page(StringBuilder content, ReadPosition start, ReadPosition end, boolean hasMore) {
        if (content.isEmpty()) {
            return new ReadPage("", 0, 0, end.column() == 1, hasMore, end);
        }
        boolean complete = end.column() == 1;
        int endLine = content.charAt(content.length() - 1) == '\n' ? end.line() - 1 : end.line();
        return new ReadPage(content.toString(), start.line(), endLine, complete, hasMore, end);
    }

    private SearchPage searchFile(RepositoryId repositoryId, String snapshotId, SnapshotFile file, SearchStart start, String query, int remaining,
                                  SearchBudget budget) {
        List<TextMatch> matches = new ArrayList<>();
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
                    matches.add(new TextMatch(file.path(), new SyntaxRange(syntax(first.position()), syntax(position)),
                            snippet.text(), snippet.truncated()));
                    if (matches.size() == remaining) {
                        ReadPosition next = advance(first.position(), first.codePoint());
                        if (next.byteOffset() == file.byteLength()) {
                            return new SearchPage(List.copyOf(matches), true, next);
                        }
                        ChunkData firstChunk = first.chunk();
                        if (next.byteOffset() == firstChunk.byteOffset() + firstChunk.bytes().length) {
                            next = nextChunkPosition(repositoryId, snapshotId, file, next, firstChunk);
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

    private void validateSearchCursorTarget(RepositoryId repositoryId, String snapshotId, SearchPosition position, String directory, SourceContext source) {
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
        SnapshotFile file = validatedFile(rows.getFirst(), source);
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

    private Optional<SnapshotFile> nextSearchFile(RepositoryId repositoryId, String snapshotId, String directory, long ordinal, SourceContext source) {
        Bson next = Filters.and(Filters.eq("repoId", repositoryId.value()), Filters.eq("snapshotId", snapshotId), Filters.eq("contentStatus", "TEXT"),
                Filters.eq("contentKind", "CODE"), Filters.gt("ordinal", ordinal), directoryFilter(directory));
        Document row = template.getCollection(IndexCollections.GIT_SNAPSHOT_FILES).find(next).sort(Sorts.ascending("ordinal")).limit(1)
                .maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS).first();
        return Objects.isNull(row) ? Optional.empty() : Optional.of(validatedFile(row, source));
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
            column += Character.charCount(codePoint);
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
    /** One-based lines/UTF-16 columns, independent of UTF-8 cursor byte offsets. */
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
    private record SearchPage(List<TextMatch> matches, boolean complete, ReadPosition position) { }
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
    private record PatchPage(String text, boolean hasNext) { }

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
