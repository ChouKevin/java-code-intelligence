package com.java.semantic.query.index;

import com.java.semantic.model.index.IndexContract;
import com.java.semantic.model.index.IndexFailure;
import com.java.semantic.model.index.RelationContract;
import com.java.semantic.model.index.RelationContract.Anchor;
import com.java.semantic.model.index.RelationContract.AnchorKind;
import com.java.semantic.model.index.RelationContract.Coverage;
import com.java.semantic.model.index.RelationContract.Direction;
import com.java.semantic.model.index.RelationContract.ExternalAnchor;
import com.java.semantic.model.index.RelationContract.ExternalTarget;
import com.java.semantic.model.index.RelationContract.Fact;
import com.java.semantic.model.index.RelationContract.FileAnchor;
import com.java.semantic.model.index.RelationContract.FileStatus;
import com.java.semantic.model.index.RelationContract.FrameworkEvidence;
import com.java.semantic.model.index.RelationContract.Kind;
import com.java.semantic.model.index.RelationContract.Platform;
import com.java.semantic.model.index.RelationContract.Provenance;
import com.java.semantic.model.index.RelationContract.Reason;
import com.java.semantic.model.index.RelationContract.Request;
import com.java.semantic.model.index.RelationContract.ResolutionState;
import com.java.semantic.model.index.RelationContract.Scope;
import com.java.semantic.model.index.RelationContract.ScopeKind;
import com.java.semantic.model.index.RelationContract.SourceSite;
import com.java.semantic.model.index.RelationContract.SourceTarget;
import com.java.semantic.model.index.RelationContract.SymbolAnchor;
import com.java.semantic.model.index.RelationContract.Target;
import com.java.semantic.model.index.RelationContract.TargetKind;
import com.java.semantic.query.application.RelationQueryInput;
import com.java.semantic.query.source.AdmittedSourceRevision;
import com.java.semantic.query.source.SourceQueryException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RelationReadBehaviorTest {

    @Test void non_java_file_is_explicitly_unsupported_not_a_corrupt_index() throws Exception {
        IndexJdbcFixture fixture = IndexJdbcFixture.nonJava(temporary, "a".repeat(40));
        fixture.relations(List.of(), new Coverage(0, 0, 0, 0, Map.of(), Map.of(), Map.of(), Map.of(), 0, 0, true));
        try (AdmittedSourceRevision source = fixture.admit()) {
            RelationContract.Result result = fixture.reader().findRelations(source,
                    request(fixture, new FileAnchor(AnchorKind.FILE, "README.md"), Direction.OUTGOING, 20, Optional.empty()));
            assertThat(result.file().orElseThrow().status()).isEqualTo(FileStatus.UNSUPPORTED);
            assertThat(result.file().orElseThrow().file().javaFile()).isFalse();
            assertThat(result.items()).isEmpty();
            assertThat(result.page().hasMore()).isFalse();
            assertThatThrownBy(() -> fixture.reader().findRelations(source,
                    request(fixture, new FileAnchor(AnchorKind.FILE, "missing.md"), Direction.OUTGOING, 20, Optional.empty())))
                    .isInstanceOfSatisfying(SourceQueryException.class,
                            failure -> assertThat(failure.code()).isEqualTo(SourceQueryException.Code.SOURCE_NOT_FOUND));
        }
    }

    @Test void absent_external_table_is_corruption_even_without_external_facts() throws Exception {
        IndexJdbcFixture fixture = IndexJdbcFixture.callable(temporary, "a".repeat(40), 0);
        fixture.relations(List.of(), coverage(Map.of(), Map.of(), Map.of(), 0, 0));
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + fixture.database)) {
            IndexJdbcFixture.insert(connection, "DROP TABLE external_targets");
        }
        fixture.seal();
        try (AdmittedSourceRevision source = fixture.admit()) {
            assertCode(() -> fixture.reader().findRelations(source,
                    request(fixture, new FileAnchor(AnchorKind.FILE, "Names.java"), Direction.OUTGOING, 20, Optional.empty())),
                    IndexFailure.Code.INDEX_UNAVAILABLE);
        }
    }

    @Test void valid_raw_fact_outside_approved_relation_scope_is_corruption() throws Exception {
        IndexJdbcFixture fixture = IndexJdbcFixture.callable(temporary, "a".repeat(40), 0);
        Fact fact = fact(fixture, Kind.REFERENCE, ResolutionState.UNRESOLVED, Optional.empty(), ScopeKind.METHOD);
        Coverage coverage = new Coverage(0, 0, 0, 1, Map.of(FileStatus.OUT_OF_SCOPE, 1L),
                Map.of(Kind.REFERENCE, 1L), Map.of(ResolutionState.UNRESOLVED, 1L), Map.of(Reason.MISSING_TYPE, 1L), 0, 0, true);
        Provenance provenance = new Provenance("d".repeat(64),
                new Platform("21", "fixture", "a".repeat(64), "b".repeat(64)), List.of("src"), List.of());
        fixture.relations(List.of(fact), coverage, provenance);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + fixture.database)) {
            IndexJdbcFixture.insert(connection, "UPDATE relation_files SET status='OUT_OF_SCOPE'");
        }
        fixture.seal();
        try (AdmittedSourceRevision source = fixture.admit()) {
            assertCode(() -> fixture.reader().findRelations(source,
                    request(fixture, new FileAnchor(AnchorKind.FILE, "Names.java"), Direction.OUTGOING, 20, Optional.empty())),
                    IndexFailure.Code.INDEX_UNAVAILABLE);
        }
    }

    @Test void byte_budget_pages_whole_facts_and_rejects_oversized_first_fact() throws Exception {
        IndexJdbcFixture fixture = IndexJdbcFixture.callable(temporary, "a".repeat(40), 0);
        Fact http = framework(fixture, Kind.HTTP_EVIDENCE, 90_000);
        Fact event = framework(fixture, Kind.EVENT_EVIDENCE, 90_000);
        fixture.relations(List.of(http, event), coverage(Map.of(Kind.HTTP_EVIDENCE, 1L, Kind.EVENT_EVIDENCE, 1L),
                Map.of(ResolutionState.CANDIDATE, 2L), Map.of(), 0, 0));
        Request request = request(fixture, new FileAnchor(AnchorKind.FILE, "Names.java"), Direction.OUTGOING, 20, Optional.empty());
        try (AdmittedSourceRevision source = fixture.admit()) {
            RelationContract.Result first = fixture.reader().findRelations(source, request);
            assertThat(first.items()).hasSize(1); assertThat(first.page().hasMore()).isTrue();
            assertThat(fixture.mapper.writeValueAsBytes(first).length).isLessThanOrEqualTo(IndexContract.MAX_RESULT_BYTES);
            RelationContract.Result second = fixture.reader().findRelations(source, request(fixture, request.anchor(), request.direction(), 20, first.page().nextCursor()));
            assertThat(second.items()).hasSize(1).doesNotContainAnyElementsOf(first.items()); assertThat(second.page().hasMore()).isFalse();
        }
        Fact huge = framework(fixture, Kind.EVENT_EVIDENCE, 200_000);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + fixture.database)) {
            IndexJdbcFixture.insert(connection, "UPDATE relations SET relation_json=? WHERE relation_id=?", fixture.mapper.writeValueAsString(huge), event.relationId());
        }
        fixture.seal();
        try (AdmittedSourceRevision source = fixture.admit()) {
            assertCode(() -> fixture.reader().findRelations(source, request), IndexFailure.Code.INDEX_RESULT_TOO_LARGE);
        }
    }
    @Test void metadata_and_first_whole_fact_share_the_response_budget() throws Exception {
        for (int vendorBytes : List.of(150_000, 200_000)) {
            IndexJdbcFixture fixture = IndexJdbcFixture.callable(temporary.resolve(Integer.toString(vendorBytes)), "a".repeat(40), 0);
            Fact fact = framework(fixture, Kind.HTTP_EVIDENCE, 20_000);
            Provenance provenance = new Provenance("d".repeat(64),
                    new Platform("21", "v".repeat(vendorBytes), "a".repeat(64), "b".repeat(64)), List.of(""), List.of());
            fixture.relations(List.of(fact), coverage(Map.of(Kind.HTTP_EVIDENCE, 1L),
                    Map.of(ResolutionState.CANDIDATE, 1L), Map.of(), 0, 0), provenance);
            fixture.seal();
            assertThat(fixture.manifest.analysisPlanDigest()).contains(provenance.analysisPlanDigest());
            try (AdmittedSourceRevision source = fixture.admit()) {
                assertCode(() -> fixture.reader().findRelations(source,
                        request(fixture, new FileAnchor(AnchorKind.FILE, "Names.java"), Direction.OUTGOING, 20, Optional.empty())),
                        IndexFailure.Code.INDEX_RESULT_TOO_LARGE);
            }
        }
    }


    @Test void retained_generation_reads_never_retarget_and_old_symbols_are_stale_in_replacement() throws Exception {
        IndexJdbcFixture fixture = IndexJdbcFixture.callable(temporary, "a".repeat(40), 0);
        Fact oldFact = fact(fixture, Kind.CALL, ResolutionState.STATIC_RESOLVED, Optional.of(sourceTarget(fixture)), ScopeKind.METHOD);
        fixture.relations(List.of(oldFact), coverage(Map.of(Kind.CALL, 1L), Map.of(ResolutionState.STATIC_RESOLVED, 1L), Map.of(), 1, 1));
        Request retainedRequest = request(fixture, new SymbolAnchor(AnchorKind.SOURCE_SYMBOL, fixture.declarations.get(1).symbolId()),
                Direction.OUTGOING, 20, Optional.empty());
        Path statePath = fixture.root.resolve("sample/state.json");
        com.java.semantic.model.index.IndexRepositoryState oldState = fixture.mapper.readValue(Files.readAllBytes(statePath),
                com.java.semantic.model.index.IndexRepositoryState.class);
        fixture.publish(List.of("A", "run"), 0);
        fixture.relations(List.of(), coverage(Map.of(), Map.of(), Map.of(), 0, 0));
        byte[] replacement = Files.readAllBytes(statePath);
        com.java.semantic.model.index.IndexRepositoryState current = fixture.mapper.readValue(replacement,
                com.java.semantic.model.index.IndexRepositoryState.class);
        com.java.semantic.model.index.IndexRepositoryState.RevisionIndexes selected = current.revisions().get(fixture.context.revision());
        Map<String, com.java.semantic.model.index.IndexRepositoryState.Receipt> generations = new HashMap<>(selected.generations());
        generations.putAll(oldState.revisions().get(fixture.context.revision()).generations());
        Files.write(statePath, fixture.mapper.writeValueAsBytes(new com.java.semantic.model.index.IndexRepositoryState(
                current.formatVersion(), current.repositoryId(), current.originFingerprint(),
                Map.of(fixture.context.revision(), new com.java.semantic.model.index.IndexRepositoryState.RevisionIndexes(
                        selected.sourceManifestDigest(), selected.selectedGeneration(), generations)))));
        try (AdmittedSourceRevision source = fixture.admit()) {
            assertThat(fixture.reader().findRelations(source, retainedRequest).items()).containsExactly(oldFact);
            assertCode(() -> fixture.reader().findRelations(source,
                    request(fixture, retainedRequest.anchor(), Direction.OUTGOING, 20, Optional.empty())),
                    IndexFailure.Code.INDEX_SYMBOL_NOT_FOUND);
            Files.write(statePath, replacement);
            assertCode(() -> fixture.reader().findRelations(source, retainedRequest), IndexFailure.Code.INDEX_GENERATION_EXPIRED);
        }
    }

    private static Fact framework(IndexJdbcFixture fixture, Kind kind, int size) {
        Fact base = fact(fixture, Kind.CALL, ResolutionState.CANDIDATE, Optional.empty(), ScopeKind.METHOD);
        return new Fact(RelationContract.factIdentity(fixture.manifest.indexGeneration(), kind, base.source(), Optional.empty()), kind,
                base.source(), ResolutionState.CANDIDATE, Optional.empty(), Optional.empty(),
                Optional.of(new FrameworkEvidence("fixture", "x".repeat(size), "written syntax", Map.of(), List.of(), List.of(), List.of(base.source()))));
    }
    @TempDir Path temporary;

    @Test void actual_sql_relation_success_is_identical_over_http_and_mcp() throws Exception {
        IndexJdbcFixture fixture = IndexJdbcFixture.callable(temporary, "a".repeat(40), 0);
        Fact fact = fact(fixture, Kind.CALL, ResolutionState.STATIC_RESOLVED, Optional.of(sourceTarget(fixture)), ScopeKind.METHOD);
        fixture.relations(List.of(fact), coverage(Map.of(Kind.CALL, 1L), Map.of(ResolutionState.STATIC_RESOLVED, 1L), Map.of(), 1, 1));
        com.java.semantic.query.source.SourceRevisionCatalog catalog = new com.java.semantic.query.source.SourceRevisionCatalog() {
            @Override public AdmittedSourceRevision admit(com.java.semantic.model.source.SourceContext context) {
                assertThat(context).isEqualTo(fixture.context); return fixture.admit();
            }
            @Override public com.java.semantic.model.source.SourceReadContract.RepositoryCollection listRepositories(com.java.semantic.model.source.SourceReadContract.RepositoryRequest request) { throw new AssertionError("not part of relation read"); }
            @Override public com.java.semantic.model.source.SourceReadContract.ContextResult getContext(com.java.semantic.model.source.SourceReadContract.ContextRequest request) { throw new AssertionError("not part of relation read"); }
        };
        com.java.semantic.query.source.RepositorySourcePort source = new com.java.semantic.query.source.RepositorySourcePort() {
            @Override public com.java.semantic.model.source.SourceReadContract.FileCollection listFiles(AdmittedSourceRevision admitted, com.java.semantic.model.source.SourceReadContract.FileListRequest request) { throw new AssertionError("not part of relation read"); }
            @Override public com.java.semantic.model.source.SourceReadContract.TextSearchResult searchText(AdmittedSourceRevision admitted, com.java.semantic.model.source.SourceReadContract.TextSearchRequest request) { throw new AssertionError("not part of relation read"); }
            @Override public com.java.semantic.model.source.SourceReadContract.SourceResult readSource(AdmittedSourceRevision admitted, com.java.semantic.model.source.SourceReadContract.ReadSourceRequest request, java.util.function.Function<Optional<String>, IndexContract.FoldResult> folds) { throw new AssertionError("not part of relation read"); }
        };
        com.java.semantic.query.application.SemanticQueryFacade facade = new com.java.semantic.query.application.SemanticQueryFacade(catalog, source, fixture.reader(), 2);
        org.springframework.test.web.servlet.MockMvc http = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new com.java.semantic.api.SemanticQueryController(facade))
                .setControllerAdvice(new com.java.semantic.api.QueryApiExceptionHandler())
                .setMessageConverters(new org.springframework.http.converter.json.JacksonJsonHttpMessageConverter(fixture.mapper)).build();
        Map<String, Object> input = Map.of("context", Map.of("repositoryId", "sample", "revision", fixture.context.revision()),
                "indexGeneration", fixture.manifest.indexGeneration(), "anchor", Map.of("kind", "FILE", "path", "Names.java"), "direction", "OUTGOING");
        String payload = http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/relations")
                .contentType("application/json").content(fixture.mapper.writeValueAsBytes(input)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn().getResponse().getContentAsString();
        io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification tool = new com.java.semantic.mcp.QueryMcpToolCatalogConfiguration()
                .mcpQueryToolSpecifications(facade, fixture.mapper).stream().filter(value -> value.tool().name().equals("find_relations")).findFirst().orElseThrow();
        io.modelcontextprotocol.spec.McpSchema.CallToolResult result = tool.callHandler().apply(null,
                io.modelcontextprotocol.spec.McpSchema.CallToolRequest.builder("find_relations").arguments(input).build());
        assertThat(result.isError()).isFalse();
        assertThat(fixture.mapper.readTree(payload)).isEqualTo(fixture.mapper.readTree(fixture.mapper.writeValueAsString(result.structuredContent())))
                .isEqualTo(fixture.mapper.readTree(((io.modelcontextprotocol.spec.McpSchema.TextContent) result.content().getFirst()).text()));
        assertThat(fixture.mapper.readTree(payload).get("items").size()).isEqualTo(1);
        assertThat(fixture.mapper.readTree(payload).get("capabilities").get("relations").asBoolean()).isTrue();
    }

    @Test void legacy_capability_refusal_leaves_outline_available() throws Exception {
        IndexJdbcFixture fixture = new IndexJdbcFixture(temporary, "a".repeat(40), List.of("A"), 0);
        try (AdmittedSourceRevision source = fixture.admit()) {
            assertCode(() -> fixture.reader().findRelations(source, request(fixture, new SymbolAnchor(AnchorKind.SOURCE_SYMBOL, fixture.declarations.getFirst().symbolId()), Direction.OUTGOING, 20, Optional.empty())), IndexFailure.Code.INDEX_CAPABILITY_UNAVAILABLE);
            assertThat(fixture.reader().outline(source, new IndexContract.OutlineRequest(fixture.context, "Names.java", 20, Optional.empty(), Optional.of(fixture.manifest.indexGeneration()))).items()).containsExactlyElementsOf(fixture.declarations);
        }
    }

    @Test void exact_incoming_excludes_unresolved_and_preserves_lambda_file_provenance_and_cursor() throws Exception {
        IndexJdbcFixture fixture = IndexJdbcFixture.callable(temporary, "a".repeat(40), 0);
        Fact resolved = fact(fixture, Kind.CALL, ResolutionState.STATIC_RESOLVED, Optional.of(sourceTarget(fixture)), ScopeKind.LAMBDA);
        Fact unresolved = fact(fixture, Kind.REFERENCE, ResolutionState.UNRESOLVED, Optional.empty(), ScopeKind.FILE);
        fixture.relations(List.of(resolved, unresolved), coverage(Map.of(Kind.CALL, 1L, Kind.REFERENCE, 1L), Map.of(ResolutionState.STATIC_RESOLVED, 1L, ResolutionState.UNRESOLVED, 1L), Map.of(Reason.MISSING_TYPE, 1L), 1, 1));
        try (AdmittedSourceRevision source = fixture.admit()) {
            RelationContract.Result incoming = fixture.reader().findRelations(source, request(fixture, new SymbolAnchor(AnchorKind.SOURCE_SYMBOL, fixture.declarations.get(1).symbolId()), Direction.INCOMING, 20, Optional.empty()));
            assertThat(incoming.items()).containsExactly(resolved);
            assertThat(incoming.items().getFirst().source().executionScope().kind()).isEqualTo(ScopeKind.LAMBDA);
            Request firstRequest = request(fixture, new FileAnchor(AnchorKind.FILE, "Names.java"), Direction.OUTGOING, 1, Optional.empty());
            RelationContract.Result first = fixture.reader().findRelations(source, firstRequest);
            assertThat(first.file()).isPresent(); assertThat(first.page().hasMore()).isTrue();
            RelationContract.Result second = fixture.reader().findRelations(source, request(fixture, firstRequest.anchor(), Direction.OUTGOING, 1, first.page().nextCursor()));
            assertThat(second.items()).hasSize(1).doesNotContainAnyElementsOf(first.items()); assertThat(second.page().hasMore()).isFalse();
            assertThatThrownBy(() -> fixture.reader().findRelations(source, request(fixture, firstRequest.anchor(), Direction.OUTGOING, 2, first.page().nextCursor()))).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> fixture.reader().findRelations(source, request(fixture, firstRequest.anchor(), Direction.OUTGOING, 1, Optional.of("forged")))).isInstanceOf(IllegalArgumentException.class);
            assertCode(() -> fixture.reader().findRelations(source, request(fixture, new SymbolAnchor(AnchorKind.SOURCE_SYMBOL, "0".repeat(64)), Direction.INCOMING, 20, Optional.empty())), IndexFailure.Code.INDEX_SYMBOL_NOT_FOUND);
        }
        assertThat(Files.exists(fixture.database.resolveSibling("index.sqlite-wal"))).isFalse();
        assertThat(Files.exists(fixture.database.resolveSibling("index.sqlite-shm"))).isFalse();
        assertThat(Files.exists(fixture.database.resolveSibling("index.sqlite-journal"))).isFalse();
    }

    @Test void multiassociation_call_statistics_count_occurrences_not_candidate_rows() throws Exception {
        IndexJdbcFixture fixture = IndexJdbcFixture.callable(temporary, "a".repeat(40), 0);
        Fact resolved = fact(fixture, Kind.CALL, ResolutionState.STATIC_RESOLVED, Optional.of(sourceTarget(fixture)), ScopeKind.METHOD);
        ExternalTarget external = external(fixture);
        Fact candidate = fact(fixture, Kind.CALL, ResolutionState.CANDIDATE, Optional.of(new Target(TargetKind.EXTERNAL_TARGET, Optional.empty(), Optional.of(external))), ScopeKind.METHOD);
        fixture.relations(List.of(resolved, candidate), coverage(Map.of(Kind.CALL, 2L), Map.of(ResolutionState.STATIC_RESOLVED, 1L, ResolutionState.CANDIDATE, 1L), Map.of(), 1, 1));
        try (AdmittedSourceRevision source = fixture.admit()) {
            RelationContract.Result result = fixture.reader().findRelations(source, request(fixture, new FileAnchor(AnchorKind.FILE, "Names.java"), Direction.OUTGOING, 20, Optional.empty()));
            assertThat(result.items()).hasSize(2); assertThat(result.relationCoverage().callOccurrences()).isEqualTo(1);
            assertThat(result.relationCoverage().resolvedCallOccurrences()).isEqualTo(1);
            assertThat(fixture.reader().findRelations(source, request(fixture, new ExternalAnchor(AnchorKind.EXTERNAL_TARGET, external.targetId()), Direction.INCOMING, 20, Optional.empty())).items()).containsExactly(candidate);
        }
    }

    @Test void actual_call_states_reject_plausible_but_false_marginal_statistics() throws Exception {
        IndexJdbcFixture fixture = IndexJdbcFixture.callable(temporary, "a".repeat(40), 0);
        Fact unresolved = fact(fixture, Kind.CALL, ResolutionState.UNRESOLVED, Optional.empty(), ScopeKind.METHOD);
        Fact resolvedReference = fact(fixture, Kind.REFERENCE, ResolutionState.STATIC_RESOLVED, Optional.of(sourceTarget(fixture)), ScopeKind.METHOD);
        fixture.relations(List.of(unresolved, resolvedReference), coverage(Map.of(Kind.CALL, 1L, Kind.REFERENCE, 1L), Map.of(ResolutionState.UNRESOLVED, 1L, ResolutionState.STATIC_RESOLVED, 1L), Map.of(Reason.MISSING_TYPE, 1L), 1, 1));
        try (AdmittedSourceRevision source = fixture.admit()) {
            assertCode(() -> fixture.reader().findRelations(source, request(fixture, new FileAnchor(AnchorKind.FILE, "Names.java"), Direction.OUTGOING, 20, Optional.empty())), IndexFailure.Code.INDEX_UNAVAILABLE);
        }
    }

    @Test void call_rows_with_zero_occurrences_are_corruption_not_capability_failure() throws Exception {
        IndexJdbcFixture fixture = IndexJdbcFixture.callable(temporary, "a".repeat(40), 0);
        fixture.relations(List.of(fact(fixture, Kind.CALL, ResolutionState.UNRESOLVED, Optional.empty(), ScopeKind.METHOD)), coverage(Map.of(Kind.CALL, 1L), Map.of(ResolutionState.UNRESOLVED, 1L), Map.of(Reason.MISSING_TYPE, 1L), 1, 0));
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + fixture.database)) {
            IndexJdbcFixture.insert(connection, "UPDATE relation_metadata SET coverage_json=json_set(coverage_json,'$.callOccurrences',0)");
        }
        fixture.seal();
        try (AdmittedSourceRevision source = fixture.admit()) {
            assertCode(() -> fixture.reader().findRelations(source, request(fixture, new FileAnchor(AnchorKind.FILE, "Names.java"), Direction.OUTGOING, 20, Optional.empty())), IndexFailure.Code.INDEX_UNAVAILABLE);
        }
    }
    @Test void filtered_continuation_binds_anchor_states_directory_and_last_row_membership() throws Exception {
        IndexJdbcFixture fixture = IndexJdbcFixture.callable(temporary, "a".repeat(40), 0);
        Fact call = fact(fixture, Kind.CALL, ResolutionState.STATIC_RESOLVED, Optional.of(sourceTarget(fixture)), ScopeKind.METHOD);
        Fact reference = fact(fixture, Kind.REFERENCE, ResolutionState.STATIC_RESOLVED, Optional.of(sourceTarget(fixture)), ScopeKind.METHOD);
        fixture.relations(List.of(reference, call), coverage(Map.of(Kind.CALL, 1L, Kind.REFERENCE, 1L),
                Map.of(ResolutionState.STATIC_RESOLVED, 2L), Map.of(), 1, 1));
        Anchor anchor = new FileAnchor(AnchorKind.FILE, "Names.java");
        Request firstRequest = request(fixture, anchor, Direction.OUTGOING, 1, Optional.empty());
        Optional<String> cursor;
        try (AdmittedSourceRevision source = fixture.admit()) {
            RelationContract.Result first = fixture.reader().findRelations(source, firstRequest);
            assertThat(first.items()).containsExactly(call);
            cursor = first.page().nextCursor();
            Request filtered = new Request(fixture.context, fixture.manifest.indexGeneration(), anchor, Direction.OUTGOING,
                    List.of(Kind.REFERENCE), List.of(ResolutionState.STATIC_RESOLVED), "", 20, Optional.empty());
            assertThat(fixture.reader().findRelations(source, filtered).items()).containsExactly(reference);
            Request outside = new Request(fixture.context, fixture.manifest.indexGeneration(), anchor, Direction.OUTGOING,
                    List.of(), List.of(), "elsewhere", 20, Optional.empty());
            assertThat(fixture.reader().findRelations(source, outside).items()).isEmpty();
            for (Request changed : List.of(
                    new Request(fixture.context, fixture.manifest.indexGeneration(), anchor, Direction.OUTGOING,
                            List.of(Kind.REFERENCE), List.of(), "", 1, cursor),
                    new Request(fixture.context, fixture.manifest.indexGeneration(), anchor, Direction.OUTGOING,
                            List.of(), List.of(ResolutionState.STATIC_RESOLVED), "", 1, cursor),
                    new Request(fixture.context, fixture.manifest.indexGeneration(), anchor, Direction.OUTGOING,
                            List.of(), List.of(), "elsewhere", 1, cursor),
                    request(fixture, new SymbolAnchor(AnchorKind.SOURCE_SYMBOL, fixture.declarations.get(1).symbolId()),
                            Direction.OUTGOING, 1, cursor))) {
                assertThatThrownBy(() -> fixture.reader().findRelations(source, changed)).isInstanceOf(IllegalArgumentException.class);
            }
        }
        // The cursor is continuation data, not authorization: the last fact must still
        // belong to the admitted projection, even when its binding is unchanged.
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + fixture.database)) {
            IndexJdbcFixture.insert(connection, "DELETE FROM relations WHERE relation_id=?", call.relationId());
            IndexJdbcFixture.insert(connection, "UPDATE relation_metadata SET coverage_json=?",
                    fixture.mapper.writeValueAsString(coverage(Map.of(Kind.REFERENCE, 1L),
                            Map.of(ResolutionState.STATIC_RESOLVED, 1L), Map.of(), 0, 0)));
        }
        fixture.seal();
        Request continuation = request(fixture, anchor, Direction.OUTGOING, 1, cursor);
        try (AdmittedSourceRevision source = fixture.admit()) {
            assertThatThrownBy(() -> fixture.reader().findRelations(source, continuation)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void occurrence_must_end_on_actual_raw_utf8_boundaries() throws Exception {
        IndexJdbcFixture fixture = IndexJdbcFixture.callable(temporary, "a".repeat(40), 0);
        IndexContract.Declaration method = fixture.declarations.get(1);
        String raw = Files.readString(fixture.tree.resolve("Names.java"));
        long offset = raw.substring(0, raw.indexOf("漢")).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        SourceSite site = new SourceSite(method.path(), method.blobId(), method.contentDigest(), method.byteLength(),
                new IndexContract.SourceSpan(offset + 1, offset + 2, 3, 3), Optional.of(method.symbolId()),
                new Scope(ScopeKind.METHOD, method.range()));
        Fact broken = new Fact(RelationContract.factIdentity(fixture.manifest.indexGeneration(), Kind.REFERENCE, site, Optional.empty()),
                Kind.REFERENCE, site, ResolutionState.UNRESOLVED, Optional.empty(), Optional.of(Reason.MISSING_TYPE), Optional.empty());
        fixture.relations(List.of(broken), coverage(Map.of(Kind.REFERENCE, 1L), Map.of(ResolutionState.UNRESOLVED, 1L),
                Map.of(Reason.MISSING_TYPE, 1L), 0, 0));
        try (AdmittedSourceRevision source = fixture.admit()) {
            assertCode(() -> fixture.reader().findRelations(source,
                    request(fixture, new FileAnchor(AnchorKind.FILE, "Names.java"), Direction.OUTGOING, 20, Optional.empty())),
                    IndexFailure.Code.INDEX_UNAVAILABLE);
        }
    }

    @Test void inventory_tampering_remains_source_failure_before_any_relation_result() throws Exception {
        IndexJdbcFixture fixture = IndexJdbcFixture.callable(temporary, "a".repeat(40), 0);
        fixture.relations(List.of(), coverage(Map.of(), Map.of(), Map.of(), 0, 0));
        Files.writeString(fixture.inventory, Files.readString(fixture.inventory).replace(fixture.entry.contentDigest().orElseThrow(), "0".repeat(64)));
        try (AdmittedSourceRevision source = fixture.admit()) {
            assertThatThrownBy(() -> fixture.reader().findRelations(source,
                    request(fixture, new FileAnchor(AnchorKind.FILE, "Names.java"), Direction.OUTGOING, 20, Optional.empty())))
                    .isInstanceOfSatisfying(SourceQueryException.class,
                            failure -> assertThat(failure.code()).isEqualTo(SourceQueryException.Code.SOURCE_UNAVAILABLE));
        }
    }

    @Test void approved_jar_named_java21_and_platform_targets_remain_distinct_for_file_and_incoming_reads() throws Exception {
        IndexJdbcFixture fixture = IndexJdbcFixture.callable(temporary, "a".repeat(40), 0);
        String digest = "c".repeat(64);
        ExternalTarget jar = new ExternalTarget(RelationContract.externalIdentity(fixture.manifest.indexGeneration(),
                "java21", digest, Optional.empty(), IndexContract.DeclarationKind.METHOD, "owned.Api", "check", "owned.Api.check()"),
                "java21", digest, Optional.empty(), IndexContract.DeclarationKind.METHOD, "owned.Api", "check", "owned.Api.check()");
        Fact jarCall = fact(fixture, Kind.CALL, ResolutionState.STATIC_RESOLVED,
                Optional.of(new Target(TargetKind.EXTERNAL_TARGET, Optional.empty(), Optional.of(jar))), ScopeKind.METHOD);
        ExternalTarget platform = external(fixture);
        Fact platformReference = fact(fixture, Kind.REFERENCE, ResolutionState.STATIC_RESOLVED,
                Optional.of(new Target(TargetKind.EXTERNAL_TARGET, Optional.empty(), Optional.of(platform))), ScopeKind.METHOD);
        Provenance provenance = new Provenance("d".repeat(64),
                new Platform("21", "fixture", "a".repeat(64), "b".repeat(64)), List.of(""),
                List.of(new RelationContract.Artifact("java21", digest, 100, 0)));
        fixture.relations(List.of(jarCall, platformReference), coverage(Map.of(Kind.CALL, 1L, Kind.REFERENCE, 1L),
                Map.of(ResolutionState.STATIC_RESOLVED, 2L), Map.of(), 1, 1), provenance);
        try (AdmittedSourceRevision source = fixture.admit()) {
            assertThat(fixture.reader().findRelations(source,
                    request(fixture, new FileAnchor(AnchorKind.FILE, "Names.java"), Direction.OUTGOING, 20, Optional.empty())).items())
                    .containsExactlyInAnyOrder(jarCall, platformReference);
            assertThat(fixture.reader().findRelations(source,
                    request(fixture, new ExternalAnchor(AnchorKind.EXTERNAL_TARGET, jar.targetId()), Direction.INCOMING, 20, Optional.empty())).items())
                    .containsExactly(jarCall);
            assertThat(fixture.reader().findRelations(source,
                    request(fixture, new ExternalAnchor(AnchorKind.EXTERNAL_TARGET, platform.targetId()), Direction.INCOMING, 20, Optional.empty())).items())
                    .containsExactly(platformReference);
        }
    }

    @Test void external_module_discriminator_still_rejects_wrong_platform_id_and_unapproved_jar_digest() throws Exception {
        IndexJdbcFixture fixture = IndexJdbcFixture.callable(temporary, "a".repeat(40), 0);
        Provenance provenance = new Provenance("d".repeat(64),
                new Platform("21", "fixture", "a".repeat(64), "b".repeat(64)), List.of(""),
                List.of(new RelationContract.Artifact("java21", "c".repeat(64), 100, 0)));
        fixture.relations(List.of(), coverage(Map.of(), Map.of(), Map.of(), 0, 0), provenance);
        List<ExternalTarget> corrupt = List.of(
                new ExternalTarget(RelationContract.externalIdentity(fixture.manifest.indexGeneration(), "other", "b".repeat(64),
                        Optional.of("java.base"), IndexContract.DeclarationKind.METHOD, "java.lang.String", "length", "java.lang.String.length()"),
                        "other", "b".repeat(64), Optional.of("java.base"), IndexContract.DeclarationKind.METHOD,
                        "java.lang.String", "length", "java.lang.String.length()"),
                new ExternalTarget(RelationContract.externalIdentity(fixture.manifest.indexGeneration(), "java21", "e".repeat(64),
                        Optional.empty(), IndexContract.DeclarationKind.METHOD, "owned.Api", "check", "owned.Api.check()"),
                        "java21", "e".repeat(64), Optional.empty(), IndexContract.DeclarationKind.METHOD,
                        "owned.Api", "check", "owned.Api.check()"),
                new ExternalTarget(RelationContract.externalIdentity(fixture.manifest.indexGeneration(), "java21", "c".repeat(64),
                        Optional.of("java.base"), IndexContract.DeclarationKind.METHOD, "java.lang.String", "length", "java.lang.String.length()"),
                        "java21", "c".repeat(64), Optional.of("java.base"), IndexContract.DeclarationKind.METHOD,
                        "java.lang.String", "length", "java.lang.String.length()"));
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + fixture.database)) {
            for (ExternalTarget target : corrupt) IndexJdbcFixture.insert(connection, "INSERT INTO external_targets VALUES(?,?,?)",
                    target.targetId(), target.artifactId(), fixture.mapper.writeValueAsString(target));
        }
        fixture.seal();
        try (AdmittedSourceRevision source = fixture.admit()) {
            for (ExternalTarget target : corrupt) assertCode(() -> fixture.reader().findRelations(source,
                    request(fixture, new ExternalAnchor(AnchorKind.EXTERNAL_TARGET, target.targetId()), Direction.INCOMING, 20, Optional.empty())),
                    IndexFailure.Code.INDEX_UNAVAILABLE);
        }
    }

    @Test void external_target_identity_requires_the_recorded_generation_and_allowed_artifact() throws Exception {
        IndexJdbcFixture fixture = IndexJdbcFixture.callable(temporary, "a".repeat(40), 0);
        ExternalTarget target = external(fixture);
        Fact candidate = fact(fixture, Kind.CALL, ResolutionState.CANDIDATE,
                Optional.of(new Target(TargetKind.EXTERNAL_TARGET, Optional.empty(), Optional.of(target))), ScopeKind.METHOD);
        fixture.relations(List.of(candidate), coverage(Map.of(Kind.CALL, 1L), Map.of(ResolutionState.CANDIDATE, 1L), Map.of(), 1, 0));
        Request incoming = request(fixture, new ExternalAnchor(AnchorKind.EXTERNAL_TARGET, target.targetId()), Direction.INCOMING, 20, Optional.empty());
        try (AdmittedSourceRevision source = fixture.admit()) {
            assertCode(() -> fixture.reader().findRelations(source,
                    request(fixture, new ExternalAnchor(AnchorKind.EXTERNAL_TARGET, "0".repeat(64)), Direction.INCOMING, 20, Optional.empty())),
                    IndexFailure.Code.INDEX_SYMBOL_NOT_FOUND);
        }
        ExternalTarget unapproved = new ExternalTarget(RelationContract.externalIdentity(fixture.manifest.indexGeneration(),
                "unapproved", "c".repeat(64), Optional.empty(), target.declarationKind(), target.qualifiedOwner(), target.name(), target.signature()),
                "unapproved", "c".repeat(64), Optional.empty(), target.declarationKind(), target.qualifiedOwner(), target.name(), target.signature());
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + fixture.database)) {
            IndexJdbcFixture.insert(connection, "INSERT INTO external_targets VALUES(?,?,?)", unapproved.targetId(), unapproved.artifactId(),
                    fixture.mapper.writeValueAsString(unapproved));
        }
        fixture.seal();
        try (AdmittedSourceRevision source = fixture.admit()) {
            assertCode(() -> fixture.reader().findRelations(source,
                    request(fixture, new ExternalAnchor(AnchorKind.EXTERNAL_TARGET, unapproved.targetId()), Direction.INCOMING, 20, Optional.empty())),
                    IndexFailure.Code.INDEX_UNAVAILABLE);
            assertThat(fixture.reader().findRelations(source, incoming).items()).containsExactly(candidate);
        }
    }


    @Test void capable_missing_tables_and_digest_mismatch_fail_closed() throws Exception {
        IndexJdbcFixture fixture = IndexJdbcFixture.callable(temporary, "a".repeat(40), 0);
        fixture.relations(List.of(), coverage(Map.of(), Map.of(), Map.of(), 0, 0));
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + fixture.database)) {
            IndexJdbcFixture.insert(connection, "UPDATE relation_metadata SET analysis_plan_digest=?", "e".repeat(64));
        }
        fixture.seal();
        try (AdmittedSourceRevision source = fixture.admit()) {
            assertCode(() -> fixture.reader().findRelations(source, request(fixture, new FileAnchor(AnchorKind.FILE, "Names.java"), Direction.OUTGOING, 20, Optional.empty())), IndexFailure.Code.INDEX_UNAVAILABLE);
        }
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + fixture.database)) {
            IndexJdbcFixture.insert(connection, "UPDATE relation_metadata SET analysis_plan_digest=?", "d".repeat(64));
            IndexJdbcFixture.insert(connection, "DROP TABLE relations");
        }
        fixture.seal();
        try (AdmittedSourceRevision source = fixture.admit()) {
            assertCode(() -> fixture.reader().findRelations(source, request(fixture, new FileAnchor(AnchorKind.FILE, "Names.java"), Direction.OUTGOING, 20, Optional.empty())), IndexFailure.Code.INDEX_UNAVAILABLE);
        }
    }

    @Test void source_corruption_keeps_source_failure_and_empty_file_has_analysis() throws Exception {
        IndexJdbcFixture fixture = IndexJdbcFixture.callable(temporary, "a".repeat(40), 0);
        fixture.relations(List.of(), coverage(Map.of(), Map.of(), Map.of(), 0, 0));
        try (AdmittedSourceRevision source = fixture.admit()) {
            RelationContract.Result empty = fixture.reader().findRelations(source, request(fixture, new FileAnchor(AnchorKind.FILE, "Names.java"), Direction.OUTGOING, 20, Optional.empty()));
            assertThat(empty.items()).isEmpty(); assertThat(empty.file().orElseThrow().status()).isEqualTo(FileStatus.ANALYZED);
        }
        Files.writeString(fixture.tree.resolve("Names.java"), "damaged");
        try (AdmittedSourceRevision source = fixture.admit()) {
            assertThatThrownBy(() -> fixture.reader().findRelations(source,
                    request(fixture, new FileAnchor(AnchorKind.FILE, "Names.java"), Direction.OUTGOING, 20, Optional.empty())))
                    .isInstanceOfSatisfying(SourceQueryException.class, failure -> assertThat(failure.code()).isEqualTo(SourceQueryException.Code.SOURCE_UNAVAILABLE));
        }
        try (AdmittedSourceRevision source = fixture.admit()) {
            assertThatThrownBy(() -> fixture.reader().findRelations(source, request(fixture, new SymbolAnchor(AnchorKind.SOURCE_SYMBOL, fixture.declarations.getFirst().symbolId()), Direction.OUTGOING, 20, Optional.empty())))
                    .isInstanceOfSatisfying(SourceQueryException.class, failure -> assertThat(failure.code()).isEqualTo(SourceQueryException.Code.SOURCE_UNAVAILABLE));
        }
    }

    @Test void strict_binding_rejects_null_filters_wrong_variants_and_invalid_directions() {
        Map<String, Object> input = new HashMap<>(Map.of("context", Map.of("repositoryId", "sample", "revision", "a".repeat(40)), "indexGeneration", "00000000-0000-0000-0000-000000000001", "anchor", Map.of("kind", "FILE", "path", "A.java"), "direction", "OUTGOING"));
        input.put("relationKinds", null);
        assertThatThrownBy(() -> RelationQueryInput.findRelations(input)).isInstanceOf(IllegalArgumentException.class);
        input.remove("relationKinds"); input.put("resolutionStates", null);
        assertThatThrownBy(() -> RelationQueryInput.findRelations(input)).isInstanceOf(IllegalArgumentException.class);
        input.remove("resolutionStates"); input.put("limit", 1.5);
        assertThatThrownBy(() -> RelationQueryInput.findRelations(input)).isInstanceOf(IllegalArgumentException.class);
        input.remove("limit"); input.put("direction", "INCOMING");
        assertThatThrownBy(() -> RelationQueryInput.findRelations(input)).isInstanceOf(IllegalArgumentException.class);
        input.put("direction", "OUTGOING"); input.put("anchor", Map.of("kind", "FILE", "path", "A.java", "symbolId", "a".repeat(64)));
        assertThatThrownBy(() -> RelationQueryInput.findRelations(input)).isInstanceOf(IllegalArgumentException.class);
        input.put("anchor", Map.of("kind", "EXTERNAL_TARGET", "targetId", "a".repeat(64)));
        assertThatThrownBy(() -> RelationQueryInput.findRelations(input)).isInstanceOf(IllegalArgumentException.class);
        input.put("anchor", Map.of("kind", "FILE", "path", "A.java")); input.put("relationKinds", List.of("REFERENCE", "CALL", "CALL"));
        assertThat(RelationQueryInput.findRelations(input).relationKinds()).containsExactly(Kind.CALL, Kind.REFERENCE);
        input.remove("indexGeneration"); assertThatThrownBy(() -> RelationQueryInput.findRelations(input)).isInstanceOf(IllegalArgumentException.class);
    }

    private static Request request(IndexJdbcFixture fixture, Anchor anchor, Direction direction, int limit, Optional<String> cursor) {
        return new Request(fixture.context, fixture.manifest.indexGeneration(), anchor, direction, List.of(), List.of(), "", limit, cursor);
    }
    private static Target sourceTarget(IndexJdbcFixture fixture) {
        return new Target(TargetKind.SOURCE_SYMBOL, Optional.of(new SourceTarget(fixture.declarations.get(1), Optional.of("A.run()"))), Optional.empty());
    }
    private static ExternalTarget external(IndexJdbcFixture fixture) {
        String id = RelationContract.externalIdentity(fixture.manifest.indexGeneration(), "java21", "b".repeat(64), Optional.of("java.base"), IndexContract.DeclarationKind.METHOD, "java.lang.String", "length", "java.lang.String.length()");
        return new ExternalTarget(id, "java21", "b".repeat(64), Optional.of("java.base"), IndexContract.DeclarationKind.METHOD, "java.lang.String", "length", "java.lang.String.length()");
    }
    private static Fact fact(IndexJdbcFixture fixture, Kind kind, ResolutionState state, Optional<Target> target, ScopeKind scopeKind) {
        IndexContract.Declaration method = fixture.declarations.get(1);
        SourceSite site = new SourceSite(method.path(), method.blobId(), method.contentDigest(), method.byteLength(), method.nameRange(),
                scopeKind == ScopeKind.FILE ? Optional.empty() : Optional.of(method.symbolId()), new Scope(scopeKind, method.range()));
        return new Fact(RelationContract.factIdentity(fixture.manifest.indexGeneration(), kind, site, target), kind, site, state, target,
                state == ResolutionState.UNRESOLVED ? Optional.of(Reason.MISSING_TYPE) : Optional.empty(), Optional.empty());
    }
    private static Coverage coverage(Map<Kind, Long> kinds, Map<ResolutionState, Long> states, Map<Reason, Long> reasons, long calls, long resolved) {
        return new Coverage(1, 1, 0, 0, Map.of(FileStatus.ANALYZED, 1L), kinds, states, reasons, calls, resolved, true);
    }
    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, IndexFailure.Code code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(IndexFailure.class, failure -> assertThat(failure.code()).isEqualTo(code));
    }
}
