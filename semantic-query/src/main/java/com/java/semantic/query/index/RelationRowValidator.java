package com.java.semantic.query.index;

import com.java.semantic.model.index.IndexContract;
import com.java.semantic.model.index.IndexFailure;
import com.java.semantic.model.index.RelationContract;
import com.java.semantic.model.index.RelationContract.ExternalTarget;
import com.java.semantic.model.index.RelationContract.Fact;
import com.java.semantic.model.index.RelationContract.Provenance;
import com.java.semantic.model.index.RelationContract.SourceSite;
import com.java.semantic.model.index.RelationContract.Target;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.databind.ObjectMapper;
import static com.java.semantic.query.index.IndexPublicationReader.require;

/** Checks concrete relation rows and linked endpoints, never name-based joins. */
final class RelationRowValidator {
    private final ObjectMapper mapper;
    private final Connection connection;
    private final IndexRowValidator syntax;
    private final String generation;
    private final Provenance provenance;

    RelationRowValidator(ObjectMapper mapper, Connection connection, IndexRowValidator syntax, String generation, Provenance provenance) {
        this.mapper = mapper; this.connection = connection; this.syntax = syntax; this.generation = generation; this.provenance = provenance;
    }
    <T> T decode(String json, Class<T> type) {
        try { return mapper.readValue(json, type); }
        catch (RuntimeException failure) { throw new IndexFailure(IndexFailure.Code.INDEX_UNAVAILABLE, failure); }
    }
    ExternalTarget external(String id, boolean requested) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT target_id,artifact_id,length(CAST(target_json AS BLOB)) AS bytes,CASE WHEN length(CAST(target_json AS BLOB))<=163840 THEN target_json ELSE NULL END AS payload FROM external_targets WHERE target_id=?")) {
            statement.setString(1, id);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new IndexFailure(requested ? IndexFailure.Code.INDEX_SYMBOL_NOT_FOUND : IndexFailure.Code.INDEX_UNAVAILABLE);
                require(row.getLong("bytes") <= IndexContract.MAX_RESULT_BYTES);
                ExternalTarget target = decode(row.getString("payload"), ExternalTarget.class);
                require(target.targetId().equals(id) && target.artifactId().equals(row.getString("artifact_id")));
                require(id.equals(RelationContract.externalIdentity(generation, target.artifactId(), target.artifactDigest(), target.module(), target.declarationKind(), target.qualifiedOwner(), target.name(), target.signature())));
                if (target.module().isPresent()) {
                    require(target.artifactId().equals("java21") && target.artifactDigest().equals(provenance.platform().modulesDigest()));
                } else {
                    require(provenance.artifacts().stream().anyMatch(artifact -> artifact.id().equals(target.artifactId()) && artifact.digest().equals(target.artifactDigest())));
                }
                return target;
            }
        }
    }
    Fact row(ResultSet row) throws SQLException {
        Fact fact = decode(row.getString("bounded_payload"), Fact.class);
        require(fact.relationId().equals(row.getString("relation_id")) && fact.source().path().equals(row.getString("path"))
                && fact.source().range().startByte() == row.getLong("start_byte") && fact.kind().name().equals(row.getString("kind"))
                && fact.resolutionState().name().equals(row.getString("resolution_state"))
                && Objects.equals(fact.source().containerSymbolId().orElse(null), row.getString("container_symbol_id"))
                && Objects.equals(fact.target().flatMap(Target::source).map(value -> value.declaration().symbolId()).orElse(null), row.getString("target_symbol_id"))
                && Objects.equals(fact.target().flatMap(Target::external).map(ExternalTarget::targetId).orElse(null), row.getString("external_target_id"))
                && fact.relationId().equals(RelationContract.factIdentity(generation, fact.kind(), fact.source(), fact.target())));
        require("ANALYZED".equals(row.getString("relation_file_status")));
        site(fact.source());
        if (fact.target().isPresent()) {
            Target target = fact.target().orElseThrow();
            if (target.source().isPresent()) {
                IndexContract.Declaration declaration = target.source().orElseThrow().declaration();
                require(syntax.symbol(declaration.symbolId(), false).equals(declaration));
            } else require(external(target.external().orElseThrow().targetId(), false).equals(target.external().orElseThrow()));
        }
        if (fact.framework().isPresent()) for (SourceSite support : fact.framework().orElseThrow().supportingEvidence()) site(support);
        return fact;
    }
    private void site(SourceSite site) throws SQLException {
        syntax.site(site);
        if (site.containerSymbolId().isPresent()) {
            IndexContract.Declaration container = syntax.symbol(site.containerSymbolId().orElseThrow(), false);
            require(container.path().equals(site.path()) && container.range().contains(site.range())
                    && container.range().contains(site.executionScope().range()));
        }
    }
}
