package com.java.semantic.query.index;

import com.java.semantic.model.index.IndexContract;
import com.java.semantic.model.index.IndexContract.*;
import com.java.semantic.model.index.IndexFailure;
import com.java.semantic.model.index.IndexManifest;
import com.java.semantic.model.source.SourceContext;
import com.java.semantic.model.source.SourceReadContract.Page;
import com.java.semantic.model.source.SourceReadContract.SemanticStatus;
import com.java.semantic.query.application.QueryCursorCodec;
import com.java.semantic.query.source.AdmittedSourceRevision;
import com.java.semantic.query.source.SourceOperationDeadline;
import com.java.semantic.query.source.SourcePathResolver;
import java.io.IOException;
import java.nio.file.Path;
import com.java.semantic.query.source.SourceQueryException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.sqlite.ProgressHandler;
import tools.jackson.databind.ObjectMapper;
import static com.java.semantic.model.index.IndexFailure.Code.*;
import static com.java.semantic.query.index.IndexPublicationReader.*;

/** Borrows the admitted source guard; all owned SQL resources close synchronously before return. */
public final class LocalIndexReader implements RepositoryIndexPort {
    private final ObjectMapper mapper;
    private final IndexPublicationReader publications;
    private final SourcePathResolver paths;

    public LocalIndexReader(Path indexRoot, ObjectMapper mapper) {
        this.mapper = mapper;
        publications = new IndexPublicationReader(indexRoot, mapper);
        paths = new SourcePathResolver(mapper);
    }

    @Override public Discovery discover(AdmittedSourceRevision source) {
        long deadline = deadline();
        try {
            IndexManifest manifest = publications.select(source, Optional.empty(), deadline).manifest();
            return new Discovery(SemanticStatus.READY, Optional.of(new IndexSummary(manifest.indexGeneration(), manifest.profile(), manifest.coverage())));
        } catch (IndexFailure failure) {
            return new Discovery(failure.code() == INDEX_NOT_PREPARED ? SemanticStatus.NOT_READY : SemanticStatus.UNAVAILABLE, Optional.empty());
        } catch (IOException | IllegalArgumentException failure) {
            return new Discovery(SemanticStatus.UNAVAILABLE, Optional.empty());
        }
    }

    @Override public Result<Declaration> searchCode(AdmittedSourceRevision source, SearchCodeRequest request) {
        String binding = binding("search", request.context(), List.of(request.query(), request.match().name(), request.kinds().toString(),
                request.owner().isPresent() ? "present:" + request.owner().orElseThrow() : "absent", request.directory()));
        List<Object> parameters = new ArrayList<>();
        StringBuilder filter = new StringBuilder();
        if (request.match() == Match.exact) { filter.append("d.name = ? COLLATE BINARY"); parameters.add(request.query()); }
        else { filter.append("substr(d.name,1,length(?)) = ? COLLATE BINARY"); parameters.add(request.query()); parameters.add(request.query()); }
        kinds(filter, parameters, "d.kind", request.kinds());
        request.owner().ifPresent(owner -> { filter.append(" AND d.owner = ? COLLATE BINARY"); parameters.add(owner); });
        directory(filter, parameters, request.directory());
        return read(source, request.context(), request.indexGeneration(), request.cursor(), binding, request.limit(),
                filter.toString(), parameters, "search", Optional.empty(), Declaration.class);
    }

    @Override public Result<Declaration> outline(AdmittedSourceRevision source, OutlineRequest request) {
        return read(source, request.context(), request.indexGeneration(), request.cursor(), binding("outline", request.context(), List.of(request.path())),
                request.limit(), "d.path = ? COLLATE BINARY", new ArrayList<>(List.of(request.path())), "outline", Optional.of(request.path()), Declaration.class);
    }

    @Override public Result<EntryCandidate> entryPoints(AdmittedSourceRevision source, EntryPointsRequest request) {
        StringBuilder filter = new StringBuilder("1=1");
        List<Object> parameters = new ArrayList<>();
        kinds(filter, parameters, "d.kind", request.candidateKinds());
        directory(filter, parameters, request.directory());
        return read(source, request.context(), request.indexGeneration(), request.cursor(), binding("entries", request.context(),
                List.of(request.candidateKinds().toString(), request.directory())), request.limit(), filter.toString(), parameters, "entries", Optional.empty(), EntryCandidate.class);
    }

    @Override public FoldResult folds(AdmittedSourceRevision source, String path, Optional<String> generation) {
        SourcePathResolver.safeFile(path);
        generation.ifPresent(IndexContract::requireGeneration);
        long deadline = SourceOperationDeadline.cap(System.nanoTime() + 2_000_000_000L);
        try {
            IndexPublicationReader.Publication publication;
            try { publication = publications.select(source, generation, deadline); }
            catch (IllegalArgumentException failure) { throw new IndexFailure(INDEX_UNAVAILABLE, failure); }
            publications.database(publication, deadline);
            Class.forName("org.sqlite.JDBC");
            FoldResult result;
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + publication.database().toUri().toASCIIString() + "?mode=ro&immutable=1");
                    SourcePathResolver.InventoryLookup inventory = paths.openVerified(source, deadline)) {
                ProgressHandler.setHandler(connection, 1000, new ProgressHandler() {
                    @Override protected int progress() { return Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline ? 1 : 0; }
                });
                try {
                    IndexManifest manifest = publication.manifest();
                    identity(connection, manifest, deadline);
                    IndexRowValidator validator = new IndexRowValidator(mapper, paths, source, inventory, connection, manifest.indexGeneration(), deadline);
                    FileOutcome file = validator.file(path, true);
                    boolean capable = manifest.profile().equals(IndexContract.currentProfile());
                    List<Fold> folds = new ArrayList<>();
                    if (capable) {
                        String sql = "SELECT f.symbol_id AS fold_id,f.path AS fold_path,f.kind AS fold_kind,f.start_byte AS fold_start,"
                                + "length(CAST(f.fold_json AS BLOB)) AS fold_bytes,"
                                + "CASE WHEN length(CAST(f.fold_json AS BLOB))<=163840 THEN f.fold_json ELSE NULL END AS bounded_fold,"
                                + "d.symbol_id,d.path,d.name,d.kind,d.owner,d.parent_id,d.start_byte,d.name_start_byte,"
                                + "length(CAST(d.declaration_json AS BLOB)) AS payload_bytes,"
                                + "CASE WHEN length(CAST(d.declaration_json AS BLOB))<=163840 THEN d.declaration_json ELSE NULL END AS bounded_payload "
                                + "FROM folds f LEFT JOIN declarations d ON d.symbol_id=f.symbol_id WHERE f.path=? ORDER BY f.start_byte,f.symbol_id LIMIT ?";
                        long collectedBytes = 0;
                        try (PreparedStatement statement = connection.prepareStatement(sql)) {
                            statement.setString(1, path); statement.setInt(2, IndexContract.MAX_DECLARATIONS + 1);
                            try (ResultSet rows = statement.executeQuery()) {
                                while (rows.next()) {
                                    check(deadline);
                                    require(file.status() == ParseStatus.PARSED);
                                    if (folds.size() == IndexContract.MAX_DECLARATIONS
                                            || rows.getLong("fold_bytes") > IndexContract.MAX_RESULT_BYTES
                                            || rows.getLong("payload_bytes") > IndexContract.MAX_RESULT_BYTES)
                                        throw new IndexFailure(INDEX_RESULT_TOO_LARGE);
                                    collectedBytes += rows.getLong("fold_bytes");
                                    if (collectedBytes > IndexContract.MAX_RESULT_BYTES) throw new IndexFailure(INDEX_RESULT_TOO_LARGE);
                                    folds.add(validator.fold(rows, path));
                                }
                            }
                        }
                    }
                    result = new FoldResult(manifest.indexGeneration(), manifest.profile(), file, capable, folds);
                    if (mapper.writeValueAsBytes(result).length > IndexContract.MAX_RESULT_BYTES) throw new IndexFailure(INDEX_RESULT_TOO_LARGE);
                } finally { ProgressHandler.clearHandler(connection); }
            }
            check(deadline);
            return result;
        } catch (LinkageError failure) { throw IndexFailure.nativeUnavailable(failure); }
        catch (SQLException | IOException | ClassNotFoundException failure) {
            check(deadline); throw new IndexFailure(INDEX_UNAVAILABLE, failure);
        } catch (SourceQueryException failure) {
            check(deadline);
            throw failure;
        }
    }

    private <T> Result<T> read(AdmittedSourceRevision source, SourceContext context, Optional<String> requestedGeneration,
            Optional<String> cursor, String binding, int limit, String filter, List<Object> parameters,
            String operation, Optional<String> outline, Class<T> type) {
        long deadline = deadline();
        if (!source.context().equals(context)) throw new IllegalArgumentException("context does not match source admission");
        List<String> position = cursor.map(value -> QueryCursorCodec.decode(value, binding, 2)).orElse(List.of());
        Optional<String> generation = requestedGeneration;
        if (!position.isEmpty()) {
            IndexContract.requireGeneration(position.getFirst());
            if (!position.get(1).matches("[0-9a-f]{64}")) throw new IllegalArgumentException("invalid cursor anchor");
            if (generation.isPresent() && !generation.orElseThrow().equals(position.getFirst())) throw new IllegalArgumentException("cursor generation mismatch");
            generation = Optional.of(position.getFirst());
        }
        try {
            IndexPublicationReader.Publication publication;
            try { publication = publications.select(source, generation, deadline); }
            catch (IllegalArgumentException failure) { throw new IndexFailure(INDEX_UNAVAILABLE, failure); }
            publications.database(publication, deadline);
            // Driver initialization is deliberately lazy, after source/index admission and digest validation.
            Class.forName("org.sqlite.JDBC");
            Result<T> result;
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + publication.database().toUri().toASCIIString() + "?mode=ro&immutable=1");
                    SourcePathResolver.InventoryLookup inventory = paths.openVerified(source, deadline)) {
                ProgressHandler.setHandler(connection, 1000, new ProgressHandler() {
                    @Override protected int progress() { return Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline ? 1 : 0; }
                });
                try {
                    identity(connection, publication.manifest(), deadline);
                    IndexRowValidator validator = new IndexRowValidator(mapper, paths, source, inventory, connection, publication.manifest().indexGeneration(), deadline);
                    Optional<FileOutcome> file = outline.map(path -> validator.file(path, true));
                    result = page(connection, publication.manifest(), validator, position, binding, limit, filter, parameters, operation, file, type, deadline);
                } finally { ProgressHandler.clearHandler(connection); }
            }
            check(deadline);
            return result;
        } catch (LinkageError failure) { throw IndexFailure.nativeUnavailable(failure); }
        catch (SQLException failure) {
            check(deadline);
            throw new IndexFailure(INDEX_UNAVAILABLE, failure);
        } catch (IOException | ClassNotFoundException failure) {
            check(deadline);
            throw new IndexFailure(INDEX_UNAVAILABLE, failure);
        } catch (SourceQueryException failure) {
            check(deadline);
            if (failure.code() == SourceQueryException.Code.SOURCE_NOT_FOUND
                    || failure.code() == SourceQueryException.Code.INVALID_ARGUMENT) throw failure;
            throw new IndexFailure(INDEX_UNAVAILABLE, failure);
        }
    }

    private <T> Result<T> page(Connection connection, IndexManifest manifest, IndexRowValidator validator, List<String> position,
            String binding, int limit, String filter, List<Object> parameters, String operation, Optional<FileOutcome> file, Class<T> type, long deadline) throws SQLException {
        boolean entries = operation.equals("entries");
        String table = entries ? "candidates" : "declarations";
        String id = entries ? "candidate_id" : "symbol_id";
        String payload = entries ? "candidate_json" : "declaration_json";
        String columns = operation.equals("search") ? "name COLLATE BINARY,path COLLATE BINARY,name_start_byte,kind COLLATE BINARY,symbol_id" :
                entries ? "path COLLATE BINARY,start_byte,kind COLLATE BINARY,candidate_id" : "path COLLATE BINARY,start_byte,symbol_id";
        String[] order = columns.split(",");
        String qualified = String.join(",", java.util.Arrays.stream(order).map(value -> "d." + value).toList());
        String indexed = entries ? "d.candidate_id,d.symbol_id,d.kind,d.path,d.start_byte" :
                "d.symbol_id,d.path,d.name,d.kind,d.owner,d.parent_id,d.start_byte,d.name_start_byte";
        String projection = indexed + ",length(CAST(d." + payload + " AS BLOB)) AS payload_bytes, CASE WHEN length(CAST(d." + payload
                + " AS BLOB)) <= " + IndexContract.MAX_RESULT_BYTES + " THEN d." + payload + " ELSE NULL END AS bounded_payload";
        if (!position.isEmpty()) {
            try (PreparedStatement anchor = connection.prepareStatement("SELECT " + projection + " FROM " + table + " d WHERE d." + id + "=? AND (" + filter + ")")) {
                anchor.setString(1, position.get(1));
                bind(anchor, parameters, 2);
                try (ResultSet row = anchor.executeQuery()) {
                    if (!row.next()) throw new IllegalArgumentException("cursor anchor does not match this query");
                    require(row.getLong("payload_bytes") <= IndexContract.MAX_RESULT_BYTES);
                    validator.row(row, type);
                    filter += " AND (" + qualified + ") > (" + String.join(",", java.util.Collections.nCopies(order.length, "?")) + ")";
                    for (String column : order) parameters.add(row.getObject(column.split(" ")[0]));
                }
            }
        }
        List<T> items = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        Result<T> empty = result(manifest, items, false, Optional.empty(), file);
        if (mapper.writeValueAsBytes(empty).length > IndexContract.MAX_RESULT_BYTES) throw new IndexFailure(INDEX_RESULT_TOO_LARGE);
        String sql = "SELECT " + projection + " FROM " + table + " d WHERE " + filter + " ORDER BY " + qualified + " LIMIT ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters, 1);
            statement.setInt(parameters.size() + 1, limit + 1);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    check(deadline);
                    if (items.size() == limit) return continued(manifest, items, ids, binding, file);
                    if (rows.getLong("payload_bytes") > IndexContract.MAX_RESULT_BYTES) {
                        if (items.isEmpty()) throw new IndexFailure(INDEX_RESULT_TOO_LARGE);
                        return continued(manifest, items, ids, binding, file);
                    }
                    T item = validator.row(rows, type);
                    String rowId = rows.getString(id);
                    items.add(item);
                    ids.add(rowId);
                    Optional<String> next = Optional.of(QueryCursorCodec.encode(binding, List.of(manifest.indexGeneration(), rowId)));
                    if (mapper.writeValueAsBytes(result(manifest, items, true, next, file)).length > IndexContract.MAX_RESULT_BYTES) {
                        Result<T> complete = result(manifest, items, false, Optional.empty(), file);
                        if (mapper.writeValueAsBytes(complete).length <= IndexContract.MAX_RESULT_BYTES && !rows.next()) {
                            check(deadline);
                            return complete;
                        }
                        items.removeLast(); ids.removeLast();
                        if (items.isEmpty()) throw new IndexFailure(INDEX_RESULT_TOO_LARGE);
                        return continued(manifest, items, ids, binding, file);
                    }
                }
            }
        }
        check(deadline);
        return result(manifest, items, false, Optional.empty(), file);
    }

    private <T> Result<T> continued(IndexManifest manifest, List<T> items, List<String> ids, String binding, Optional<FileOutcome> file) {
        return result(manifest, items, true, Optional.of(QueryCursorCodec.encode(binding, List.of(manifest.indexGeneration(), ids.getLast()))), file);
    }
    private static <T> Result<T> result(IndexManifest manifest, List<T> items, boolean more, Optional<String> cursor, Optional<FileOutcome> file) {
        return new Result<>(manifest.context(), manifest.indexGeneration(), manifest.profile(), manifest.coverage(), AnalysisLevel.SYNTAX,
                items, new Page(items.size(), more, cursor), file);
    }
    private void identity(Connection connection, IndexManifest manifest, long deadline) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT singleton,repository_id,revision,generation,source_manifest_digest,inventory_digest,length(CAST(profile_json AS BLOB)) AS profile_bytes,CASE WHEN length(CAST(profile_json AS BLOB))<=65536 THEN profile_json ELSE NULL END AS profile_json FROM identity"); ResultSet row = statement.executeQuery()) {
            check(deadline);
            require(row.next() && row.getLong("profile_bytes") <= 65536 && row.getInt("singleton") == 1 && manifest.context().repositoryId().equals(row.getString("repository_id"))
                    && manifest.context().revision().equals(row.getString("revision")) && manifest.indexGeneration().equals(row.getString("generation"))
                    && manifest.sourceManifestDigest().equals(row.getString("source_manifest_digest")) && manifest.inventoryDigest().equals(row.getString("inventory_digest"))
                    && profile(row.getString("profile_json")).equals(manifest.profile()) && !row.next());
        }
    }
    private Profile profile(String json) {
        try { return mapper.readValue(json, Profile.class); }
        catch (RuntimeException failure) { throw new IndexFailure(INDEX_UNAVAILABLE, failure); }
    }
    private static void bind(PreparedStatement statement, List<Object> parameters, int start) throws SQLException {
        for (Object value : parameters) statement.setObject(start++, value);
    }
    private static void kinds(StringBuilder filter, List<Object> parameters, String column, List<? extends Enum<?>> kinds) {
        if (kinds.isEmpty()) return;
        filter.append(" AND ").append(column).append(" IN (").append(String.join(",", java.util.Collections.nCopies(kinds.size(), "?"))).append(')');
        for (Enum<?> kind : kinds) parameters.add(kind.name());
    }
    private static void directory(StringBuilder filter, List<Object> parameters, String directory) {
        if (directory.isEmpty()) return;
        SourcePathResolver.safeDirectory(directory);
        String prefix = directory + "/";
        filter.append(" AND substr(d.path,1,length(?)) = ? COLLATE BINARY");
        parameters.add(prefix); parameters.add(prefix);
    }
    private static String binding(String operation, SourceContext context, List<String> fields) {
        List<String> all = new ArrayList<>(List.of(context.repositoryId(), context.revision())); all.addAll(fields);
        return QueryCursorCodec.binding("index-v1-" + operation, all);
    }
    private static long deadline() { return SourceOperationDeadline.cap(System.nanoTime() + 5_000_000_000L); }
}
