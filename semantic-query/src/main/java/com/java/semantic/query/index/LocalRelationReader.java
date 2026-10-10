package com.java.semantic.query.index;

import com.java.semantic.model.index.IndexContract;
import com.java.semantic.model.index.IndexFailure;
import com.java.semantic.model.index.IndexManifest;
import com.java.semantic.model.index.RelationContract;
import com.java.semantic.model.index.RelationContract.Coverage;
import com.java.semantic.model.index.RelationContract.Direction;
import com.java.semantic.model.index.RelationContract.ExternalAnchor;
import com.java.semantic.model.index.RelationContract.Fact;
import com.java.semantic.model.index.RelationContract.FileAnalysis;
import com.java.semantic.model.index.RelationContract.FileAnchor;
import com.java.semantic.model.index.RelationContract.FileStatus;
import com.java.semantic.model.index.RelationContract.Kind;
import com.java.semantic.model.index.RelationContract.Provenance;
import com.java.semantic.model.index.RelationContract.Reason;
import com.java.semantic.model.index.RelationContract.Request;
import com.java.semantic.model.index.RelationContract.ResolutionState;
import com.java.semantic.model.index.RelationContract.SymbolAnchor;
import com.java.semantic.model.source.SourceReadContract.Page;
import com.java.semantic.query.application.QueryCursorCodec;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.ObjectMapper;
import static com.java.semantic.query.index.IndexPublicationReader.check;
import static com.java.semantic.query.index.IndexPublicationReader.require;

/** Borrows an admitted immutable connection; owns and closes only statements. */
final class LocalRelationReader {
    private static final String PROJECTION = "r.relation_id,r.path,r.start_byte,r.kind,r.resolution_state,r.container_symbol_id,r.target_symbol_id,r.external_target_id,(SELECT rf.status FROM relation_files rf WHERE rf.path=r.path) AS relation_file_status,length(CAST(r.relation_json AS BLOB)) AS payload_bytes,CASE WHEN length(CAST(r.relation_json AS BLOB))<=163840 THEN r.relation_json ELSE NULL END AS bounded_payload";
    private final ObjectMapper mapper;
    LocalRelationReader(ObjectMapper mapper) { this.mapper = mapper; }

    RelationContract.Result read(Connection connection, IndexManifest manifest, IndexRowValidator syntax, Request request, long deadline) throws SQLException {
        // Required capable schema must exist even if this page has no external endpoints.
        try (PreparedStatement schema = connection.prepareStatement("SELECT target_id,artifact_id,target_json FROM external_targets LIMIT 0")) {
            schema.executeQuery().close();
        }
        String binding = QueryCursorCodec.binding("index-v1-relations", List.of(request.context().repositoryId(), request.context().revision(), request.indexGeneration(), request.anchor().toString(), request.direction().name(), request.relationKinds().toString(), request.resolutionStates().toString(), request.directory(), Integer.toString(request.limit())));
        List<String> position = request.cursor().map(cursor -> QueryCursorCodec.decode(cursor, binding, 2)).orElse(List.of());
        if (!position.isEmpty() && (!position.getFirst().equals(request.indexGeneration()) || !position.get(1).matches("[0-9a-f]{64}"))) throw new IllegalArgumentException("invalid relation cursor");
        Provenance provenance;
        Coverage coverage;
        try (PreparedStatement statement = connection.prepareStatement("SELECT singleton,analysis_plan_digest,length(CAST(provenance_json AS BLOB)) AS provenance_bytes,length(CAST(coverage_json AS BLOB)) AS coverage_bytes,CASE WHEN length(CAST(provenance_json AS BLOB))<=163840 THEN provenance_json ELSE NULL END AS provenance_json,CASE WHEN length(CAST(coverage_json AS BLOB))<=163840 THEN coverage_json ELSE NULL END AS coverage_json FROM relation_metadata"); ResultSet row = statement.executeQuery()) {
            require(row.next() && row.getInt("singleton") == 1);
            if (row.getLong("provenance_bytes") > IndexContract.MAX_RESULT_BYTES
                    || row.getLong("coverage_bytes") > IndexContract.MAX_RESULT_BYTES) {
                throw new IndexFailure(IndexFailure.Code.INDEX_RESULT_TOO_LARGE);
            }
            provenance = decode(row.getString("provenance_json"), Provenance.class);
            coverage = decode(row.getString("coverage_json"), Coverage.class);
            require(manifest.analysisPlanDigest().orElseThrow().equals(row.getString("analysis_plan_digest")) && provenance.analysisPlanDigest().equals(row.getString("analysis_plan_digest")) && !row.next());
        }
        require(manifest.coverage().javaFiles() == coverage.scopeFiles() + coverage.outsideScopeJavaFiles());
        validateCoverage(connection, coverage, provenance, deadline);
        RelationRowValidator validator = new RelationRowValidator(mapper, connection, syntax, manifest.indexGeneration(), provenance);
        Optional<FileAnalysis> file = Optional.empty();
        StringBuilder filter = new StringBuilder();
        List<Object> parameters = new ArrayList<>();
        if (request.anchor() instanceof SymbolAnchor anchor) {
            syntax.symbol(anchor.symbolId(), true);
            filter.append(request.direction() == Direction.OUTGOING ? "r.container_symbol_id=?" : "r.target_symbol_id=?"); parameters.add(anchor.symbolId());
        } else if (request.anchor() instanceof ExternalAnchor anchor) {
            validator.external(anchor.targetId(), true); filter.append("r.external_target_id=?"); parameters.add(anchor.targetId());
        } else if (request.anchor() instanceof FileAnchor anchor) {
            IndexContract.FileOutcome outcome = syntax.file(anchor.path(), true);
            syntax.fileSource(outcome);
            if (!outcome.javaFile()) {
                file = Optional.of(new FileAnalysis(outcome, FileStatus.UNSUPPORTED));
            } else try (PreparedStatement statement = connection.prepareStatement("SELECT status FROM relation_files WHERE path=?")) {
                statement.setString(1, anchor.path());
                try (ResultSet row = statement.executeQuery()) { require(row.next()); file = Optional.of(new FileAnalysis(outcome, enumValue(row.getString(1), FileStatus.class))); }
            }
            filter.append("r.path=? COLLATE BINARY"); parameters.add(anchor.path());
        }
        kinds(filter, parameters, "r.kind", request.relationKinds());
        kinds(filter, parameters, "r.resolution_state", request.resolutionStates());
        if (!request.directory().isEmpty()) { filter.append(" AND substr(r.path,1,length(?))=? COLLATE BINARY"); parameters.add(request.directory() + "/"); parameters.add(request.directory() + "/"); }
        if (!position.isEmpty()) {
            try (PreparedStatement statement = connection.prepareStatement("SELECT " + PROJECTION + " FROM relations r WHERE r.relation_id=? AND (" + filter + ")")) {
                statement.setString(1, position.get(1)); bind(statement, parameters, 2);
                try (ResultSet row = statement.executeQuery()) {
                    if (!row.next()) throw new IllegalArgumentException("cursor anchor does not match query");
                    require(row.getLong("payload_bytes") <= IndexContract.MAX_RESULT_BYTES); validator.row(row);
                    filter.append(" AND (r.path COLLATE BINARY,r.start_byte,r.kind COLLATE BINARY,r.relation_id)>(?,?,?,?)");
                    parameters.add(row.getString("path")); parameters.add(row.getLong("start_byte")); parameters.add(row.getString("kind")); parameters.add(row.getString("relation_id"));
                }
            }
        }
        List<Fact> items = new ArrayList<>();
        if (mapper.writeValueAsBytes(result(manifest, request, coverage, provenance, items, false, Optional.empty(), file)).length > IndexContract.MAX_RESULT_BYTES) throw new IndexFailure(IndexFailure.Code.INDEX_RESULT_TOO_LARGE);
        try (PreparedStatement statement = connection.prepareStatement("SELECT " + PROJECTION + " FROM relations r WHERE " + filter + " ORDER BY r.path COLLATE BINARY,r.start_byte,r.kind COLLATE BINARY,r.relation_id LIMIT ?")) {
            bind(statement, parameters, 1); statement.setInt(parameters.size() + 1, request.limit() + 1);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    check(deadline);
                    if (items.size() == request.limit()) return continued(manifest, request, coverage, provenance, items, binding, file);
                    if (rows.getLong("payload_bytes") > IndexContract.MAX_RESULT_BYTES) {
                        if (items.isEmpty()) throw new IndexFailure(IndexFailure.Code.INDEX_RESULT_TOO_LARGE);
                        return continued(manifest, request, coverage, provenance, items, binding, file);
                    }
                    items.add(validator.row(rows));
                    Optional<String> next = Optional.of(QueryCursorCodec.encode(binding, List.of(manifest.indexGeneration(), items.getLast().relationId())));
                    if (mapper.writeValueAsBytes(result(manifest, request, coverage, provenance, items, true, next, file)).length > IndexContract.MAX_RESULT_BYTES) {
                        RelationContract.Result complete = result(manifest, request, coverage, provenance, items, false, Optional.empty(), file);
                        if (mapper.writeValueAsBytes(complete).length <= IndexContract.MAX_RESULT_BYTES && !rows.next()) { check(deadline); return complete; }
                        items.removeLast();
                        if (items.isEmpty()) throw new IndexFailure(IndexFailure.Code.INDEX_RESULT_TOO_LARGE);
                        return continued(manifest, request, coverage, provenance, items, binding, file);
                    }
                }
            }
        }
        check(deadline);
        return result(manifest, request, coverage, provenance, items, false, Optional.empty(), file);
    }

    private void validateCoverage(Connection connection, Coverage coverage, Provenance provenance, long deadline) throws SQLException {
        Map<Kind, Long> kinds = new EnumMap<>(Kind.class);
        Map<ResolutionState, Long> states = new EnumMap<>(ResolutionState.class);
        Map<Reason, Long> reasons = new EnumMap<>(Reason.class);
        try (PreparedStatement statement = connection.prepareStatement("SELECT kind,resolution_state,json_extract(relation_json,'$.unresolvedReason') AS reason,count(*) AS total FROM relations GROUP BY kind,resolution_state,reason"); ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                check(deadline); long count = rows.getLong("total");
                kinds.merge(enumValue(rows.getString("kind"), Kind.class), count, Long::sum);
                ResolutionState state = enumValue(rows.getString("resolution_state"), ResolutionState.class); states.merge(state, count, Long::sum);
                if (state == ResolutionState.UNRESOLVED) reasons.merge(enumValue(rows.getString("reason"), Reason.class), count, Long::sum);
                else require(java.util.Objects.isNull(rows.getString("reason")));
            }
        }
        require(countsEqual(kinds, coverage.kindCounts()) && countsEqual(states, coverage.stateCounts()) && countsEqual(reasons, coverage.reasonCounts()));
        try (PreparedStatement statement = connection.prepareStatement("SELECT count(*) AS occurrences,coalesce(sum(resolved),0) AS resolved FROM (SELECT path,start_byte,json_extract(relation_json,'$.source.range.endByte'),max(CASE WHEN resolution_state='STATIC_RESOLVED' THEN 1 ELSE 0 END) AS resolved FROM relations WHERE kind IN ('CALL','CONSTRUCT','METHOD_REFERENCE') GROUP BY path,start_byte,json_extract(relation_json,'$.source.range.endByte'))"); ResultSet row = statement.executeQuery()) {
            require(row.next() && row.getLong("occurrences") == coverage.callOccurrences() && row.getLong("resolved") == coverage.resolvedCallOccurrences());
        }
        Map<FileStatus, Long> files = new EnumMap<>(FileStatus.class);
        try (PreparedStatement statement = connection.prepareStatement("SELECT f.path,f.status AS parse_status,f.java_file,rf.status FROM files f LEFT JOIN relation_files rf ON f.path=rf.path UNION ALL SELECT rf.path,NULL,0,rf.status FROM relation_files rf LEFT JOIN files f ON f.path=rf.path WHERE f.path IS NULL"); ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                check(deadline);
                if (rows.getInt("java_file") != 1) { require(java.util.Objects.isNull(rows.getString("status"))); continue; }
                FileStatus status = enumValue(rows.getString("status"), FileStatus.class);
                String path = rows.getString("path");
                boolean inScope = provenance.sourceRoots().stream().anyMatch(root -> root.isEmpty() || path.startsWith(root + "/"));
                require((status != FileStatus.OUT_OF_SCOPE) == inScope);
                if (inScope) require(status.name().equals(rows.getString("parse_status").equals("PARSED") ? "ANALYZED" : rows.getString("parse_status")));
                files.merge(status, 1L, Long::sum);
            }
        }
        require(countsEqual(files, coverage.fileCounts()));
    }
    private static <E extends Enum<E>> boolean countsEqual(Map<E, Long> actual, Map<E, Long> expected) {
        for (Map.Entry<E, Long> entry : expected.entrySet()) if (actual.getOrDefault(entry.getKey(), 0L).longValue() != entry.getValue().longValue()) return false;
        for (Map.Entry<E, Long> entry : actual.entrySet()) if (expected.getOrDefault(entry.getKey(), 0L).longValue() != entry.getValue().longValue()) return false;
        return true;
    }
    private static <E extends Enum<E>> E enumValue(String value, Class<E> type) {
        try { return Enum.valueOf(type, value); }
        catch (RuntimeException failure) { throw new IndexFailure(IndexFailure.Code.INDEX_UNAVAILABLE, failure); }
    }
    private <T> T decode(String json, Class<T> type) {
        try { return mapper.readValue(json, type); }
        catch (RuntimeException failure) { throw new IndexFailure(IndexFailure.Code.INDEX_UNAVAILABLE, failure); }
    }
    private static void kinds(StringBuilder filter, List<Object> parameters, String column, List<? extends Enum<?>> values) {
        if (values.isEmpty()) return;
        filter.append(" AND ").append(column).append(" IN (").append(String.join(",", java.util.Collections.nCopies(values.size(), "?"))).append(')');
        for (Enum<?> value : values) parameters.add(value.name());
    }
    private static void bind(PreparedStatement statement, List<Object> values, int start) throws SQLException { for (Object value : values) statement.setObject(start++, value); }
    private static RelationContract.Result continued(IndexManifest manifest, Request request, Coverage coverage, Provenance provenance, List<Fact> items, String binding, Optional<FileAnalysis> file) {
        return result(manifest, request, coverage, provenance, items, true, Optional.of(QueryCursorCodec.encode(binding, List.of(manifest.indexGeneration(), items.getLast().relationId()))), file);
    }
    private static RelationContract.Result result(IndexManifest manifest, Request request, Coverage coverage, Provenance provenance, List<Fact> items, boolean more, Optional<String> cursor, Optional<FileAnalysis> file) {
        return new RelationContract.Result(manifest.context(), manifest.indexGeneration(), manifest.profile(), IndexContract.capabilities(manifest.profile()), manifest.coverage(), coverage, provenance, IndexContract.AnalysisLevel.STATIC, request.anchor(), request.direction(), items, new Page(items.size(), more, cursor), file);
    }
}
