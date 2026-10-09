package com.java.semantic.query.index;

import com.java.semantic.model.index.IndexContract.*;
import com.java.semantic.model.index.IndexFailure;
import com.java.semantic.query.application.IndexQueryInput;
import com.java.semantic.query.source.AdmittedSourceRevision;
import com.java.semantic.model.index.IndexContract;
import com.java.semantic.model.index.IndexManifest;
import com.java.semantic.model.source.SourceReadContract.SemanticStatus;
import com.java.semantic.query.source.SourceReadLocks;
import com.java.semantic.query.source.SourceQueryException;
import com.java.semantic.query.application.SemanticQueryInput;
import com.java.semantic.query.config.SourceAccessProperties;
import com.java.semantic.query.source.BoundedSourceReader;
import com.java.semantic.query.source.SourcePathResolver;
import com.java.semantic.model.source.SourceReadContract.ReadSourceRequest;
import com.java.semantic.model.source.SourceReadContract.SourceResult;
import com.java.semantic.model.source.SourceReadContract.SummaryOutcome;
import com.java.semantic.model.source.SourceReadContract.View;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class IndexReadBehaviorTest {
    @TempDir Path temporary;
    @Test void bare_auto_preserves_real_inventory_failure_during_fold_lookup_after_admission() throws Exception {
        List<String> names = java.util.stream.IntStream.range(0, 110).mapToObj(index -> "Name" + index).toList();
        IndexJdbcFixture fixture = new IndexJdbcFixture(temporary, "a".repeat(40), names, 0);
        SourceAccessProperties properties = new SourceAccessProperties(fixture.lockRoot,
                Path.of(System.getProperty("source.test.rg", "/usr/bin/rg")), List.of("sample"), 65_536,
                Duration.ofSeconds(5), Duration.ofSeconds(2), Duration.ofSeconds(2), 2);
        BoundedSourceReader reader = new BoundedSourceReader(properties, new SourcePathResolver(fixture.mapper), fixture.mapper);
        ReadSourceRequest request = SemanticQueryInput.readSource(Map.of("context",
                Map.of("repositoryId", "sample", "revision", fixture.context.revision()), "path", "Names.java"));
        try (AdmittedSourceRevision source = fixture.admit()) {
            assertThatThrownBy(() -> reader.read(source, request, pin -> {
                // Admission and initial raw inspection succeeded; the borrowed inventory now fails verification.
                try { Files.writeString(fixture.inventory, "{broken\n"); }
                catch (IOException failure) { throw new UncheckedIOException(failure); }
                return fixture.reader().folds(source, "Names.java", pin);
            })).isInstanceOfSatisfying(SourceQueryException.class,
                    failure -> assertThat(failure.code()).isEqualTo(SourceQueryException.Code.SOURCE_UNAVAILABLE));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"auto", "summary"})
    void source_byte_corruption_during_fold_lookup_never_becomes_index_fallback(String view) throws Exception {
        IndexJdbcFixture fixture = IndexJdbcFixture.callable(temporary, "a".repeat(40), 100);
        Path path = fixture.tree.resolve("Names.java");
        String original = Files.readString(path);
        SourceAccessProperties properties = new SourceAccessProperties(fixture.lockRoot,
                Path.of(System.getProperty("source.test.rg", "/usr/bin/rg")), List.of("sample"), 65_536,
                Duration.ofSeconds(5), Duration.ofSeconds(2), Duration.ofSeconds(2), 2);
        BoundedSourceReader reader = new BoundedSourceReader(properties, new SourcePathResolver(fixture.mapper), fixture.mapper);
        ReadSourceRequest request = SemanticQueryInput.readSource(Map.of("context",
                Map.of("repositoryId", "sample", "revision", fixture.context.revision()), "path", "Names.java", "view", view));
        try (AdmittedSourceRevision source = fixture.admit()) {
            assertThatThrownBy(() -> reader.read(source, request, pin -> {
                // Preserve length and valid UTF-8; only the committed source digest now differs.
                try { Files.writeString(path, original.replace("漢", "字")); }
                catch (IOException failure) { throw new UncheckedIOException(failure); }
                return fixture.reader().folds(source, "Names.java", pin);
            })).isInstanceOfSatisfying(SourceQueryException.class,
                    failure -> assertThat(failure.code()).isEqualTo(SourceQueryException.Code.SOURCE_UNAVAILABLE));
        }
    }

    @Test void bare_auto_still_falls_back_for_real_broken_index_with_healthy_source() throws Exception {
        List<String> names = java.util.stream.IntStream.range(0, 110).mapToObj(index -> "Name" + index).toList();
        IndexJdbcFixture fixture = new IndexJdbcFixture(temporary, "a".repeat(40), names, 0);
        Files.writeString(fixture.database, "broken SQLite");
        String original = Files.readString(fixture.tree.resolve("Names.java"));
        SourceAccessProperties properties = new SourceAccessProperties(fixture.lockRoot,
                Path.of(System.getProperty("source.test.rg", "/usr/bin/rg")), List.of("sample"), 65_536,
                Duration.ofSeconds(5), Duration.ofSeconds(2), Duration.ofSeconds(2), 2);
        BoundedSourceReader reader = new BoundedSourceReader(properties, new SourcePathResolver(fixture.mapper), fixture.mapper);
        ReadSourceRequest request = SemanticQueryInput.readSource(Map.of("context",
                Map.of("repositoryId", "sample", "revision", fixture.context.revision()), "path", "Names.java"));
        try (AdmittedSourceRevision source = fixture.admit()) {
            SourceResult result = reader.read(source, request, pin -> fixture.reader().folds(source, "Names.java", pin));
            assertThat(result.effectiveView()).isEqualTo(View.RAW);
            assertThat(result.summaryOutcome()).isEqualTo(SummaryOutcome.INDEX_UNAVAILABLE);
            assertThat(result.segments()).singleElement().satisfies(segment ->
                    assertThat(segment.content()).contains(original));
        }
    }

    @Test void oversized_fold_payload_is_rejected_before_json_materialization() throws Exception {
        IndexJdbcFixture fixture = IndexJdbcFixture.callable(temporary, "a".repeat(40), 0);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + fixture.database)) {
            IndexJdbcFixture.insert(connection, "UPDATE folds SET fold_json=?", "x".repeat(IndexContract.MAX_RESULT_BYTES + 1));
        }
        fixture.seal();
        try (AdmittedSourceRevision source = fixture.admit()) {
            assertThatThrownBy(() -> fixture.reader().folds(source, "Names.java", Optional.empty()))
                    .isInstanceOfSatisfying(IndexFailure.class, failure -> assertThat(failure.code()).isEqualTo(IndexFailure.Code.INDEX_RESULT_TOO_LARGE));
        }
    }

    @Test void folds_return_raw_interiors_and_reject_corruption_instead_of_empty_evidence() throws Exception {
        IndexJdbcFixture fixture = IndexJdbcFixture.callable(temporary, "a".repeat(40), 0);
        try (AdmittedSourceRevision source = fixture.admit()) {
            FoldResult result = fixture.reader().folds(source, "Names.java", Optional.of(fixture.manifest.indexGeneration()));
            assertThat(result.foldCapable()).isTrue();
            assertThat(result.file().status()).isEqualTo(ParseStatus.PARSED);
            assertThat(result.folds()).hasSize(1);
            Fold fold = result.folds().getFirst();
            byte[] bytes = Files.readAllBytes(fixture.tree.resolve("Names.java"));
            assertThat(new String(bytes, Math.toIntExact(fold.interior().startByte()),
                    Math.toIntExact(fold.interior().endByte() - fold.interior().startByte()), StandardCharsets.UTF_8))
                    .isEqualTo("\r\n  String s = \"漢\";\r\n ");
            assertThat(fold.declarationId()).isEqualTo(fixture.declarations.get(1).symbolId());
            assertThatThrownBy(() -> fixture.reader().folds(source, "Missing.java", Optional.empty()))
                    .isInstanceOfSatisfying(SourceQueryException.class, failure -> assertThat(failure.code()).isEqualTo(SourceQueryException.Code.SOURCE_NOT_FOUND));
        }
        String text = Files.readString(fixture.tree.resolve("Names.java"));
        int insideUtf8 = text.substring(0, text.indexOf("漢")).getBytes(StandardCharsets.UTF_8).length + 1;
        String declarationId = fixture.declarations.get(1).symbolId();
        for (Fold corrupt : List.of(
                new Fold(declarationId, "METHOD", new SourceSpan(24, 25, 99, 99)),
                new Fold(declarationId, "METHOD", new SourceSpan(insideUtf8, insideUtf8 + 1, 3, 3)),
                new Fold(declarationId, "CONSTRUCTOR", new SourceSpan(24, 25, 3, 3)))) {
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + fixture.database)) {
                IndexJdbcFixture.insert(connection, "UPDATE folds SET kind=?,start_byte=?,fold_json=?", corrupt.kind(),
                        corrupt.interior().startByte(), fixture.mapper.writeValueAsString(corrupt));
            }
            fixture.seal();
            try (AdmittedSourceRevision source = fixture.admit()) {
                assertThatThrownBy(() -> fixture.reader().folds(source, "Names.java", Optional.empty()))
                        .isInstanceOfSatisfying(IndexFailure.class, failure -> assertThat(failure.code()).isEqualTo(IndexFailure.Code.INDEX_UNAVAILABLE));
            }
        }
    }

    @Test void known_schema_one_keeps_navigation_without_reading_an_absent_fold_table() throws Exception {
        IndexJdbcFixture fixture = IndexJdbcFixture.callable(temporary, "a".repeat(40), 0);
        fixture.legacyProfile();
        try (AdmittedSourceRevision source = fixture.admit()) {
            LocalIndexReader reader = fixture.reader();
            assertThat(reader.searchCode(source, fixture.search("run", Match.exact, 20, Optional.empty())).items())
                    .extracting(Declaration::name).containsExactly("run");
            assertThat(reader.outline(source, new OutlineRequest(fixture.context, "Names.java", 20, Optional.empty(), Optional.empty())).items()).hasSize(2);
            assertThat(reader.entryPoints(source, new EntryPointsRequest(fixture.context, List.of(), "", 20, Optional.empty(), Optional.empty())).items()).isEmpty();
            FoldResult result = reader.folds(source, "Names.java", Optional.empty());
            assertThat(result.foldCapable()).isFalse();
            assertThat(result.folds()).isEmpty();
            assertThat(result.profile().schemaVersion()).isEqualTo(1);
        }
    }

    @Test void schema_two_missing_fold_metadata_is_unavailable_not_legacy() throws Exception {
        IndexJdbcFixture fixture = IndexJdbcFixture.callable(temporary, "a".repeat(40), 0);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + fixture.database)) {
            IndexJdbcFixture.insert(connection, "DROP TABLE folds");
        }
        fixture.seal();
        try (AdmittedSourceRevision source = fixture.admit()) {
            assertThatThrownBy(() -> fixture.reader().folds(source, "Names.java", Optional.empty()))
                    .isInstanceOfSatisfying(IndexFailure.class, failure -> assertThat(failure.code()).isEqualTo(IndexFailure.Code.INDEX_UNAVAILABLE));
        }
    }


    @Test void literal_case_unicode_and_metacharacters_do_not_become_patterns() throws Exception {
        IndexJdbcFixture fixture = new IndexJdbcFixture(temporary, "a".repeat(40), List.of("Order", "OrderExtra", "order", "Ünicode", "𐐀Name", "A%", "A_", "A*"), 0);
        try (AdmittedSourceRevision source = fixture.admit()) {
            LocalIndexReader reader = fixture.reader();
            assertThat(reader.searchCode(source, fixture.search("Order", Match.prefix, 20, Optional.empty())).items()).extracting(Declaration::name).containsExactly("Order", "OrderExtra");
            for (String name : List.of("order", "Ünicode", "𐐀Name", "A%", "A_", "A*")) {
                assertThat(reader.searchCode(source, fixture.search(name, Match.prefix, 20, Optional.empty())).items()).extracting(Declaration::name).containsExactly(name);
            }
            assertThat(reader.outline(source, new OutlineRequest(fixture.context, "Names.java", 100, Optional.empty(), Optional.empty())).file().orElseThrow().status()).isEqualTo(ParseStatus.PARSED);
        }
    }

    @Test void continuation_is_bound_to_filters_and_exact_published_generation() throws Exception {
        IndexJdbcFixture fixture = new IndexJdbcFixture(temporary, "a".repeat(40), List.of("A", "AB", "AC"), 0);
        LocalIndexReader reader = fixture.reader();
        try (AdmittedSourceRevision source = fixture.admit()) {
            Result<Declaration> first = reader.searchCode(source, fixture.search("A", Match.prefix, 1, Optional.empty()));
            assertThat(reader.searchCode(source, fixture.search("A", Match.prefix, 1, first.page().nextCursor())).items()).extracting(Declaration::name).containsExactly("AB");
            assertThatThrownBy(() -> reader.searchCode(source, fixture.search("AB", Match.prefix, 1, first.page().nextCursor()))).isInstanceOf(IllegalArgumentException.class);
            fixture.publish(List.of("A", "AB", "AC"), 0);
            assertThatThrownBy(() -> reader.searchCode(source, fixture.search("A", Match.prefix, 1, first.page().nextCursor())))
                    .isInstanceOfSatisfying(IndexFailure.class, failure -> assertThat(failure.code()).isEqualTo(IndexFailure.Code.INDEX_GENERATION_EXPIRED));
        }
    }

    @Test void byte_budget_returns_complete_progressing_pages_and_never_skips_large_rows() throws Exception {
        IndexJdbcFixture fixture = new IndexJdbcFixture(temporary, "a".repeat(40), List.of("A", "AB", "AC"), 90_000);
        try (AdmittedSourceRevision source = fixture.admit()) {
            Result<Declaration> first = fixture.reader().searchCode(source, fixture.search("A", Match.prefix, 20, Optional.empty()));
            assertThat(first.items()).hasSize(1);
            assertThat(first.page().hasMore()).isTrue();
            Result<Declaration> second = fixture.reader().searchCode(source, fixture.search("A", Match.prefix, 20, first.page().nextCursor()));
            assertThat(second.items()).extracting(Declaration::name).containsExactly("AB");
            assertThat(fixture.mapper.writeValueAsBytes(first).length).isLessThanOrEqualTo(163_840);
        }
        fixture.publish(List.of("A", "AB", "AC"), 170_000);
        try (AdmittedSourceRevision source = fixture.admit()) {
            assertThatThrownBy(() -> fixture.reader().searchCode(source, fixture.search("A", Match.prefix, 20, Optional.empty())))
                    .isInstanceOfSatisfying(IndexFailure.class, failure -> assertThat(failure.code()).isEqualTo(IndexFailure.Code.INDEX_RESULT_TOO_LARGE));
        }
    }

    @Test void sealed_database_with_wrong_indexed_columns_cannot_claim_source_evidence() throws Exception {
        IndexJdbcFixture fixture = new IndexJdbcFixture(temporary, "a".repeat(40), List.of("A"), 0);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + fixture.database); Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE declarations SET name_start_byte=1");
        }
        fixture.seal();
        try (AdmittedSourceRevision source = fixture.admit()) {
            assertThatThrownBy(() -> fixture.reader().searchCode(source, fixture.search("A", Match.exact, 20, Optional.empty())))
                    .isInstanceOfSatisfying(IndexFailure.class, failure -> assertThat(failure.code()).isEqualTo(IndexFailure.Code.INDEX_UNAVAILABLE));
        }
    }

    @Test void discovery_does_not_open_or_validate_the_database_and_queries_do_not_create_it() throws Exception {
        IndexJdbcFixture fixture = new IndexJdbcFixture(temporary, "a".repeat(40), List.of("A"), 0);
        Files.delete(fixture.database);
        try (AdmittedSourceRevision source = fixture.admit()) {
            assertThat(fixture.reader().discover(source).index()).isPresent();
            assertThatThrownBy(() -> fixture.reader().searchCode(source, fixture.search("A", Match.exact, 20, Optional.empty()))).isInstanceOf(IndexFailure.class);
            assertThat(Files.exists(fixture.database)).isFalse();
        }
    }

    @Test void both_transports_bind_required_match_typed_limits_and_closed_fields() {
        Map<String, Object> context = Map.of("repositoryId", "sample", "revision", "a".repeat(40));
        assertThatThrownBy(() -> IndexQueryInput.searchCode(Map.of("context", context, "query", "A"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IndexQueryInput.searchCode(Map.of("context", context, "query", "A", "match", "contains"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IndexQueryInput.searchCode(Map.of("context", context, "query", "A", "match", "exact", "limit", "20"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IndexQueryInput.outline(Map.of("context", context, "path", "../secret"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IndexQueryInput.entryPoints(Map.of("context", context, "regex", true))).isInstanceOf(IllegalArgumentException.class);
        SearchCodeRequest bound = IndexQueryInput.searchCode(Map.of("context", context, "query", "A", "match", "exact", "kinds", List.of("METHOD", "CLASS", "CLASS")));
        assertThat(bound.kinds()).containsExactly(DeclarationKind.CLASS, DeclarationKind.METHOD);
    }

    @Test void simultaneous_exact_revisions_do_not_follow_a_shared_selected_pointer() throws Exception {
        IndexJdbcFixture a = new IndexJdbcFixture(temporary, "a".repeat(40), List.of("Alpha"), 0);
        IndexJdbcFixture b = new IndexJdbcFixture(temporary, "b".repeat(40), List.of("Beta"), 0);
        SourceReadLocks locks = new SourceReadLocks(a.lockRoot);
        try (AdmittedSourceRevision sourceA = a.admit(locks); AdmittedSourceRevision sourceB = b.admit(locks);
                ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            LocalIndexReader reader = a.reader();
            Future<Result<Declaration>> resultA = executor.submit(() -> reader.searchCode(sourceA, a.search("Alpha", Match.exact, 20, Optional.empty())));
            Future<Result<Declaration>> resultB = executor.submit(() -> reader.searchCode(sourceB, b.search("Beta", Match.exact, 20, Optional.empty())));
            assertThat(resultA.get().context()).isEqualTo(a.context);
            assertThat(resultA.get().items()).extracting(Declaration::name).containsExactly("Alpha");
            assertThat(resultB.get().context()).isEqualTo(b.context);
            assertThat(resultB.get().items()).extracting(Declaration::name).containsExactly("Beta");
        }
    }

    @Test void entry_candidates_preserve_syntax_uncertainty_and_source_provenance() throws Exception {
        IndexJdbcFixture fixture = new IndexJdbcFixture(temporary, "a".repeat(40), List.of("handle"), 0);
        Declaration declaration = fixture.declarations.getFirst();
        Evidence evidence = new Evidence(EvidenceKind.AMBIGUOUS_SIMPLE, Optional.of("GetMapping"), Map.of("value", List.of("/orders")),
                List.of("produces"), declaration.range());
        EntryCandidate candidate = new EntryCandidate(IndexContract.identity(fixture.manifest.indexGeneration(),
                declaration.symbolId(), "HTTP_MAPPING", Long.toString(evidence.range().startByte())), CandidateKind.HTTP_MAPPING, declaration, evidence);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + fixture.database)) {
            IndexJdbcFixture.insert(connection, "INSERT INTO candidates VALUES(?,?,?,?,?,?)", candidate.candidateId(), declaration.symbolId(),
                    candidate.kind().name(), declaration.path(), evidence.range().startByte(), fixture.mapper.writeValueAsString(candidate));
        }
        fixture.seal();
        try (AdmittedSourceRevision source = fixture.admit()) {
            Result<EntryCandidate> result = fixture.reader().entryPoints(source,
                    new EntryPointsRequest(fixture.context, List.of(CandidateKind.HTTP_MAPPING), "", 20, Optional.empty(), Optional.empty()));
            assertThat(result.items()).containsExactly(candidate);
            assertThat(result.analysisLevel()).isEqualTo(AnalysisLevel.SYNTAX);
        }
    }

    @Test void failed_parse_outline_is_explicit_while_absent_source_path_is_not_an_empty_outline() throws Exception {
        IndexJdbcFixture fixture = new IndexJdbcFixture(temporary, "a".repeat(40), List.of("A"), 0);
        FileOutcome failed = new FileOutcome("Names.java", true, ParseStatus.PARSE_FAILED, fixture.entry.byteLength(),
                fixture.entry.blobId(), fixture.entry.contentDigest(), List.of(new Diagnostic("PARSE_ERROR", Optional.empty())));
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + fixture.database)) {
            IndexJdbcFixture.insert(connection, "UPDATE files SET status=?,outcome_json=?", "PARSE_FAILED", fixture.mapper.writeValueAsString(failed));
            IndexJdbcFixture.insert(connection, "DELETE FROM declarations");
        }
        IndexManifest original = fixture.manifest;
        Coverage partial = new Coverage(1, 0, 1, 0, 0, 0, false);
        fixture.manifest = new IndexManifest(original.formatVersion(), original.context(), original.originFingerprint(),
                original.indexGeneration(), original.sourceManifestDigest(), original.inventoryDigest(), original.profile(),
                partial, original.databaseBytes(), original.databaseDigest(), original.preparedAt());
        fixture.seal();
        try (AdmittedSourceRevision source = fixture.admit()) {
            Result<Declaration> result = fixture.reader().outline(source, new OutlineRequest(fixture.context, "Names.java", 20, Optional.empty(), Optional.empty()));
            assertThat(result.items()).isEmpty();
            assertThat(result.file()).contains(failed);
            assertThat(result.coverage()).isEqualTo(partial);
            Result<Declaration> search = fixture.reader().searchCode(source, fixture.search("A", Match.exact, 20, Optional.empty()));
            assertThat(search.items()).isEmpty();
            assertThat(search.coverage()).isEqualTo(partial);
            assertThatThrownBy(() -> fixture.reader().outline(source, new OutlineRequest(fixture.context, "Missing.java", 20, Optional.empty(), Optional.empty())))
                    .isInstanceOfSatisfying(SourceQueryException.class, failure ->
                            assertThat(failure.code()).isEqualTo(SourceQueryException.Code.SOURCE_NOT_FOUND));
        }
    }

    @Test void discovery_absence_corruption_and_future_profile_remain_index_local() throws Exception {
        IndexJdbcFixture fixture = new IndexJdbcFixture(temporary, "a".repeat(40), List.of("A"), 0);
        Path state = fixture.root.resolve("sample/state.json");
        byte[] healthy = Files.readAllBytes(state);
        try (AdmittedSourceRevision source = fixture.admit()) {
            Files.delete(state);
            assertThat(fixture.reader().discover(source).status()).isEqualTo(SemanticStatus.NOT_READY);
            Files.writeString(state, "{broken");
            assertThat(fixture.reader().discover(source).status()).isEqualTo(SemanticStatus.UNAVAILABLE);
            Files.write(state, healthy);
            IndexManifest original = fixture.manifest;
            fixture.manifest = new IndexManifest(1, original.context(), original.originFingerprint(), original.indexGeneration(),
                    original.sourceManifestDigest(), original.inventoryDigest(), new Profile(2, 1, "JavaParser", "future", "JAVA_21", true, 1),
                    original.coverage(), original.databaseBytes(), original.databaseDigest(), original.preparedAt());
            fixture.seal();
            assertThat(fixture.reader().discover(source).status()).isEqualTo(SemanticStatus.UNAVAILABLE);
            assertThatThrownBy(() -> fixture.reader().searchCode(source, fixture.search("A", Match.exact, 20, Optional.empty())))
                    .isInstanceOfSatisfying(IndexFailure.class, failure -> assertThat(failure.code()).isEqualTo(IndexFailure.Code.INDEX_INCOMPATIBLE));
            assertThat(Files.readString(fixture.tree.resolve("Names.java"))).isEqualTo("A\n");
        }
    }

    @Test void oversized_parent_signature_does_not_consume_the_child_result_budget_or_hide_corrupt_evidence() throws Exception {
        IndexJdbcFixture fixture = IndexJdbcFixture.oversizedParent(temporary, "a".repeat(40));
        Declaration parent = fixture.declarations.getFirst();
        Declaration child = fixture.declarations.get(1);
        assertThat(parent.signature().getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                .isGreaterThan(IndexContract.MAX_RESULT_BYTES);
        assertThat(fixture.mapper.writeValueAsBytes(child).length).isLessThan(IndexContract.MAX_RESULT_BYTES);
        assertThat(fixture.entry.byteLength()).isLessThanOrEqualTo(IndexContract.MAX_FILE_BYTES);
        try (AdmittedSourceRevision source = fixture.admit()) {
            Result<Declaration> result = fixture.reader().searchCode(source, fixture.search("child", Match.exact, 20, Optional.empty()));
            assertThat(result.items()).containsExactly(child);
            assertThat(result.page().hasMore()).isFalse();
            assertThat(fixture.mapper.writeValueAsBytes(result).length).isLessThanOrEqualTo(IndexContract.MAX_RESULT_BYTES);
            assertThatThrownBy(() -> fixture.reader().searchCode(source, fixture.search("Parent", Match.exact, 20, Optional.empty())))
                    .isInstanceOfSatisfying(IndexFailure.class, failure ->
                            assertThat(failure.code()).isEqualTo(IndexFailure.Code.INDEX_RESULT_TOO_LARGE));
        }
        Declaration wrongSpelling = new Declaration(parent.symbolId(), "Wrong", parent.kind(), parent.owner(), parent.parentSymbolId(),
                parent.path(), parent.blobId(), parent.contentDigest(), parent.byteLength(), parent.range(), parent.nameRange(), parent.signature());
        Declaration wrongProvenance = new Declaration(parent.symbolId(), parent.name(), parent.kind(), parent.owner(), parent.parentSymbolId(),
                parent.path(), "b".repeat(40), parent.contentDigest(), parent.byteLength(), parent.range(), parent.nameRange(), parent.signature());
        for (Declaration forged : List.of(wrongSpelling, wrongProvenance)) {
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + fixture.database)) {
                // Keep searchable columns consistent: source evidence, not a column mismatch, must reject the parent.
                IndexJdbcFixture.insert(connection, "UPDATE declarations SET name=?,declaration_json=? WHERE symbol_id=?",
                        forged.name(), fixture.mapper.writeValueAsString(forged), forged.symbolId());
            }
            fixture.seal();
            try (AdmittedSourceRevision source = fixture.admit()) {
                assertThatThrownBy(() -> fixture.reader().searchCode(source, fixture.search("child", Match.exact, 20, Optional.empty())))
                        .isInstanceOfSatisfying(IndexFailure.class, failure ->
                                assertThat(failure.code()).isEqualTo(IndexFailure.Code.INDEX_UNAVAILABLE));
            }
        }
    }

    @Test void raw_unicode_escapes_crlf_and_supplementary_names_survive_outline_continuation() throws Exception {
        IndexJdbcFixture fixture = new IndexJdbcFixture(temporary, "a".repeat(40), List.of("A", "𐐀Name"), 0,
                "\r\n", Map.of("A", "\\u0041"));
        try (AdmittedSourceRevision source = fixture.admit()) {
            Result<Declaration> first = fixture.reader().outline(source,
                    new OutlineRequest(fixture.context, "Names.java", 1, Optional.empty(), Optional.empty()));
            assertThat(first.items()).singleElement().satisfies(declaration -> {
                assertThat(declaration.name()).isEqualTo("A");
                assertThat(declaration.nameRange()).isEqualTo(new SourceSpan(0, 6, 1, 1));
            });
            Result<Declaration> second = fixture.reader().outline(source,
                    new OutlineRequest(fixture.context, "Names.java", 1, first.page().nextCursor(), Optional.empty()));
            assertThat(second.items()).singleElement().satisfies(declaration -> {
                assertThat(declaration.name()).isEqualTo("𐐀Name");
                assertThat(declaration.nameRange()).isEqualTo(new SourceSpan(8, 16, 2, 2));
            });
            assertThat(second.page().hasMore()).isFalse();
        }
    }

    @Test void matching_payload_and_columns_cannot_authorize_a_split_utf8_name_range() throws Exception {
        IndexJdbcFixture fixture = new IndexJdbcFixture(temporary, "a".repeat(40), List.of("𐐀Name"), 0);
        Declaration original = fixture.declarations.getFirst();
        Declaration forged = new Declaration(IndexContract.identity(fixture.manifest.indexGeneration(), original.path(), "CLASS", "1"),
                original.name(), original.kind(), original.owner(), original.parentSymbolId(), original.path(), original.blobId(),
                original.contentDigest(), original.byteLength(), original.range(), new SourceSpan(1, 3, 1, 1), original.signature());
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + fixture.database)) {
            IndexJdbcFixture.insert(connection, "UPDATE declarations SET symbol_id=?,name_start_byte=?,declaration_json=?",
                    forged.symbolId(), 1, fixture.mapper.writeValueAsString(forged));
        }
        fixture.seal();
        try (AdmittedSourceRevision source = fixture.admit()) {
            assertThatThrownBy(() -> fixture.reader().searchCode(source, fixture.search(original.name(), Match.exact, 20, Optional.empty())))
                    .isInstanceOfSatisfying(IndexFailure.class, failure -> assertThat(failure.code()).isEqualTo(IndexFailure.Code.INDEX_UNAVAILABLE));
        }
    }

    @Test void healthy_sqlite_integrity_is_not_authority_for_a_wrong_source_identity() throws Exception {
        IndexJdbcFixture fixture = new IndexJdbcFixture(temporary, "a".repeat(40), List.of("A"), 0);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + fixture.database); Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE identity SET revision='" + "b".repeat(40) + "'");
        }
        fixture.seal();
        try (AdmittedSourceRevision source = fixture.admit()) {
            assertThatThrownBy(() -> fixture.reader().searchCode(source, fixture.search("A", Match.exact, 20, Optional.empty())))
                    .isInstanceOfSatisfying(IndexFailure.class, failure -> assertThat(failure.code()).isEqualTo(IndexFailure.Code.INDEX_UNAVAILABLE));
        }
    }
}
