package com.java.semantic.indexer.index;

import com.java.semantic.model.index.IndexContract;
import com.java.semantic.model.index.RelationContract;
import com.java.semantic.model.index.RelationContract.Coverage;
import com.java.semantic.model.index.RelationContract.ExternalTarget;
import com.java.semantic.model.index.RelationContract.Fact;
import com.java.semantic.model.index.RelationContract.FileAnalysis;
import com.java.semantic.model.index.RelationContract.FileStatus;
import com.java.semantic.model.index.RelationContract.Kind;
import com.java.semantic.model.index.RelationContract.Provenance;
import com.java.semantic.model.index.RelationContract.Reason;
import com.java.semantic.model.index.RelationContract.ResolutionState;
import com.java.semantic.model.index.RelationContract.SourceSite;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import tools.jackson.databind.ObjectMapper;

/** Borrows the build transaction; no relation becomes durable before the whole build commits. */
public final class SqliteRelationProjection implements AutoCloseable {
    private record Occurrence(String path, long start, long end, Kind kind) { }
    private final Connection connection;
    private final ObjectMapper mapper;
    private final IndexBuildInput input;
    private final Runnable capacityCheck;
    private final PreparedStatement edges;
    private final PreparedStatement external;
    private final PreparedStatement externalLookup;
    private final PreparedStatement files;
    private final PreparedStatement declaration;
    private final PreparedStatement sourceFile;
    private final Map<FileStatus, Long> fileCounts = new EnumMap<>(FileStatus.class);
    private final Map<Kind, Long> kinds = new EnumMap<>(Kind.class);
    private final Map<ResolutionState, Long> states = new EnumMap<>(ResolutionState.class);
    private final Map<Reason, Long> reasons = new EnumMap<>(Reason.class);
    private final Set<Occurrence> calls = new HashSet<>();
    private final Set<Occurrence> resolvedCalls = new HashSet<>();
    private long associations;
    private String cachedPath = "";
    private IndexSourceCoordinates coordinates;

    public SqliteRelationProjection(Connection connection, ObjectMapper mapper, IndexBuildInput input,
            Runnable capacityCheck) throws SQLException {
        this.connection = connection;
        this.mapper = mapper;
        this.input = input;
        this.capacityCheck = capacityCheck;
        try (Statement statement = connection.createStatement()) {
            for (String sql : List.of(
                    "CREATE TABLE relation_metadata (singleton INTEGER PRIMARY KEY CHECK(singleton=1), analysis_plan_digest TEXT NOT NULL, provenance_json TEXT NOT NULL, coverage_json TEXT NOT NULL)",
                    "CREATE TABLE relation_files (path TEXT PRIMARY KEY REFERENCES files(path), status TEXT NOT NULL)",
                    "CREATE TABLE external_targets (target_id TEXT PRIMARY KEY, artifact_id TEXT NOT NULL, target_json TEXT NOT NULL)",
                    "CREATE TABLE relations (relation_id TEXT PRIMARY KEY, path TEXT NOT NULL REFERENCES files(path), start_byte INTEGER NOT NULL, kind TEXT NOT NULL, resolution_state TEXT NOT NULL, container_symbol_id TEXT REFERENCES declarations(symbol_id), target_symbol_id TEXT REFERENCES declarations(symbol_id), external_target_id TEXT REFERENCES external_targets(target_id), relation_json TEXT NOT NULL, CHECK(NOT(target_symbol_id IS NOT NULL AND external_target_id IS NOT NULL)))",
                    "CREATE INDEX relation_outgoing ON relations(container_symbol_id,path,start_byte,kind,relation_id)",
                    "CREATE INDEX relation_incoming ON relations(target_symbol_id,path,start_byte,kind,relation_id)",
                    "CREATE INDEX relation_external ON relations(external_target_id,path,start_byte,kind,relation_id)",
                    "CREATE INDEX relation_file_order ON relations(path,start_byte,kind,relation_id)")) statement.execute(sql);
        }
        edges = connection.prepareStatement("INSERT INTO relations VALUES(?,?,?,?,?,?,?,?,?)");
        external = connection.prepareStatement("INSERT OR IGNORE INTO external_targets VALUES(?,?,?)");
        externalLookup = connection.prepareStatement("SELECT target_json FROM external_targets WHERE target_id=?");
        files = connection.prepareStatement("INSERT INTO relation_files VALUES(?,?)");
        declaration = connection.prepareStatement("SELECT declaration_json FROM declarations WHERE symbol_id=?");
        sourceFile = connection.prepareStatement("SELECT outcome_json FROM files WHERE path=?");
    }

    public void accept(Fact fact) {
        if (++associations > RelationContract.MAX_FACTS) throw new IllegalStateException("relation association limit exceeded");
        try {
            if (!fact.relationId().equals(RelationContract.factIdentity(input.generation(), fact.kind(), fact.source(), fact.target())))
                throw new IOException("relation identity mismatch");
            validateSite(fact.source());
            if (fact.framework().isPresent()) {
                for (SourceSite supporting : fact.framework().orElseThrow().supportingEvidence()) validateSite(supporting);
            }
            String targetSymbol = null;
            String externalId = null;
            if (fact.target().isPresent()) {
                RelationContract.Target target = fact.target().orElseThrow();
                if (target.source().isPresent()) {
                    IndexContract.Declaration endpoint = target.source().orElseThrow().declaration();
                    if (!endpoint.equals(loadDeclaration(endpoint.symbolId()))) throw new IOException("source endpoint mismatch");
                    targetSymbol = endpoint.symbolId();
                } else {
                    ExternalTarget endpoint = target.external().orElseThrow();
                    if (!endpoint.targetId().equals(RelationContract.externalIdentity(input.generation(), endpoint.artifactId(),
                            endpoint.artifactDigest(), endpoint.module(), endpoint.declarationKind(), endpoint.qualifiedOwner(), endpoint.name(), endpoint.signature())))
                        throw new IOException("external identity mismatch");
                    String json = mapper.writeValueAsString(endpoint);
                    external.setString(1, endpoint.targetId()); external.setString(2, endpoint.artifactId()); external.setString(3, json);
                    external.executeUpdate();
                    externalLookup.setString(1, endpoint.targetId());
                    try (ResultSet row = externalLookup.executeQuery()) {
                        if (!row.next() || !mapper.readValue(row.getString(1), ExternalTarget.class).equals(endpoint))
                            throw new IOException("conflicting external target");
                    }
                    externalId = endpoint.targetId();
                }
            }
            edges.setString(1, fact.relationId()); edges.setString(2, fact.source().path());
            edges.setLong(3, fact.source().range().startByte()); edges.setString(4, fact.kind().name());
            edges.setString(5, fact.resolutionState().name()); edges.setString(6, fact.source().containerSymbolId().orElse(null));
            edges.setString(7, targetSymbol); edges.setString(8, externalId); edges.setString(9, mapper.writeValueAsString(fact));
            edges.executeUpdate();
            kinds.merge(fact.kind(), 1L, Long::sum); states.merge(fact.resolutionState(), 1L, Long::sum);
            fact.unresolvedReason().ifPresent(reason -> reasons.merge(reason, 1L, Long::sum));
            if (fact.kind() == Kind.CALL || fact.kind() == Kind.CONSTRUCT || fact.kind() == Kind.METHOD_REFERENCE) {
                Occurrence occurrence = new Occurrence(fact.source().path(), fact.source().range().startByte(), fact.source().range().endByte(), fact.kind());
                calls.add(occurrence);
                if (fact.resolutionState() == ResolutionState.STATIC_RESOLVED) resolvedCalls.add(occurrence);
            }
            if (associations % 32 == 0) capacityCheck.run();
        } catch (SQLException | IOException failure) { throw IndexFiles.unavailable(failure); }
    }

    private IndexContract.Declaration loadDeclaration(String id) throws SQLException, IOException {
        declaration.setString(1, id);
        try (ResultSet row = declaration.executeQuery()) {
            if (!row.next()) throw new IOException("source declaration missing");
            return mapper.readValue(row.getString(1), IndexContract.Declaration.class);
        }
    }

    private void validateSite(SourceSite site) throws SQLException, IOException {
        sourceFile.setString(1, site.path());
        try (ResultSet row = sourceFile.executeQuery()) {
            if (!row.next()) throw new IOException("relation source missing");
            IndexContract.FileOutcome file = mapper.readValue(row.getString(1), IndexContract.FileOutcome.class);
            if (!file.blobId().equals(java.util.Optional.of(site.blobId()))
                    || !file.contentDigest().equals(java.util.Optional.of(site.contentDigest())) || file.byteLength() != site.byteLength())
                throw new IOException("relation source provenance mismatch");
        }
        if (!cachedPath.equals(site.path())) {
            byte[] bytes = IndexFiles.read(input.tree().resolve(site.path()), IndexContract.MAX_FILE_BYTES);
            if (!com.java.semantic.indexer.source.DurableSourceFiles.sha256(bytes).equals(site.contentDigest()))
                throw new IOException("relation source changed");
            coordinates = new IndexSourceCoordinates(bytes);
            cachedPath = site.path();
        }
        coordinates.validate(site.range()); coordinates.validate(site.executionScope().range());
        if (site.containerSymbolId().isPresent()) {
            IndexContract.Declaration container = loadDeclaration(site.containerSymbolId().orElseThrow());
            if (!container.path().equals(site.path()) || !container.range().contains(site.range()))
                throw new IOException("relation container mismatch");
        }
    }

    public void recordFile(FileAnalysis file) {
        try {
            sourceFile.setString(1, file.file().path());
            try (ResultSet row = sourceFile.executeQuery()) {
                if (!row.next() || !mapper.readValue(row.getString(1), IndexContract.FileOutcome.class).equals(file.file()))
                    throw new SQLException("relation file outcome mismatch");
            }
            files.setString(1, file.file().path()); files.setString(2, file.status().name()); files.executeUpdate();
            fileCounts.merge(file.status(), 1L, Long::sum);
        } catch (SQLException failure) { throw IndexFiles.unavailable(failure); }
    }

    public Coverage coverage() {
        long outside = fileCounts.getOrDefault(FileStatus.OUT_OF_SCOPE, 0L);
        long processed = fileCounts.getOrDefault(FileStatus.ANALYZED, 0L);
        long scope = fileCounts.values().stream().mapToLong(Long::longValue).sum() - outside;
        return new Coverage(scope, processed, scope - processed, outside, fileCounts, kinds, states, reasons,
                calls.size(), resolvedCalls.size(), scope == processed);
    }

    public void finish(Coverage coverage, Provenance provenance) {
        if (!coverage.equals(coverage())) throw new IllegalArgumentException("relation coverage mismatch");
        try (PreparedStatement metadata = connection.prepareStatement("INSERT INTO relation_metadata VALUES(1,?,?,?)")) {
            try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("SELECT target_json FROM external_targets")) {
                while (rows.next()) {
                    ExternalTarget target = mapper.readValue(rows.getString(1), ExternalTarget.class);
                    boolean approved = target.module().isPresent()
                            ? target.artifactId().equals("java21") && target.artifactDigest().equals(provenance.platform().modulesDigest())
                            : provenance.artifacts().stream().anyMatch(artifact -> artifact.id().equals(target.artifactId())
                                    && artifact.digest().equals(target.artifactDigest()));
                    if (!approved) throw new IOException("external provenance mismatch");
                }
            }
            metadata.setString(1, provenance.analysisPlanDigest()); metadata.setString(2, mapper.writeValueAsString(provenance));
            metadata.setString(3, mapper.writeValueAsString(coverage)); metadata.executeUpdate();
            capacityCheck.run();
        } catch (SQLException | IOException failure) { throw IndexFiles.unavailable(failure); }
    }
    static void validate(Connection connection, ObjectMapper mapper, String planDigest) throws SQLException, IOException {
        try (Statement statement = connection.createStatement()) {
            try (ResultSet rows = statement.executeQuery("SELECT * FROM relation_metadata")) {
                if (!rows.next() || rows.getInt("singleton") != 1
                        || !planDigest.equals(rows.getString("analysis_plan_digest"))
                        || !planDigest.equals(mapper.readValue(rows.getString("provenance_json"), Provenance.class).analysisPlanDigest()))
                    throw new IOException("relation metadata identity mismatch");
                mapper.readValue(rows.getString("coverage_json"), Coverage.class);
                if (rows.next()) throw new IOException("duplicate relation metadata");
            }
            try (ResultSet rows = statement.executeQuery("SELECT * FROM external_targets")) {
                while (rows.next()) {
                    ExternalTarget target = mapper.readValue(rows.getString("target_json"), ExternalTarget.class);
                    if (!target.targetId().equals(rows.getString("target_id")) || !target.artifactId().equals(rows.getString("artifact_id")))
                        throw new IOException("external target payload mismatch");
                }
            }
            try (ResultSet rows = statement.executeQuery("SELECT * FROM relations")) {
                while (rows.next()) {
                    Fact fact = mapper.readValue(rows.getString("relation_json"), Fact.class);
                    String sourceTarget = fact.target().flatMap(RelationContract.Target::source)
                            .map(target -> target.declaration().symbolId()).orElse(null);
                    String externalTarget = fact.target().flatMap(RelationContract.Target::external)
                            .map(ExternalTarget::targetId).orElse(null);
                    if (!fact.relationId().equals(rows.getString("relation_id"))
                            || !fact.source().path().equals(rows.getString("path"))
                            || fact.source().range().startByte() != rows.getLong("start_byte")
                            || !fact.kind().name().equals(rows.getString("kind"))
                            || !fact.resolutionState().name().equals(rows.getString("resolution_state"))
                            || !Objects.equals(fact.source().containerSymbolId().orElse(null), rows.getString("container_symbol_id"))
                            || !Objects.equals(sourceTarget, rows.getString("target_symbol_id"))
                            || !Objects.equals(externalTarget, rows.getString("external_target_id")))
                        throw new IOException("relation payload mismatch");
                }
            }
            try (ResultSet rows = statement.executeQuery("SELECT r.path,r.status,f.outcome_json FROM relation_files r JOIN files f ON f.path=r.path")) {
                while (rows.next()) {
                    IndexContract.FileOutcome file = mapper.readValue(rows.getString("outcome_json"), IndexContract.FileOutcome.class);
                    new FileAnalysis(file, FileStatus.valueOf(rows.getString("status")));
                    if (!file.path().equals(rows.getString("path"))) throw new IOException("relation file payload mismatch");
                }
            }
        }
    }


    @Override public void close() throws SQLException {
        edges.close(); external.close(); externalLookup.close(); files.close(); declaration.close(); sourceFile.close();
    }
}
