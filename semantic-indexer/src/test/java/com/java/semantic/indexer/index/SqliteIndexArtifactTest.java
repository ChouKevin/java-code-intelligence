package com.java.semantic.indexer.index;

import com.java.semantic.indexer.job.IndexJobId;
import com.java.semantic.model.index.IndexContract;
import com.java.semantic.model.index.IndexContract.*;
import com.java.semantic.model.index.IndexFailure;
import com.java.semantic.model.index.IndexManifest;
import com.java.semantic.model.index.IndexRepositoryState.Receipt;
import com.java.semantic.model.index.RelationContract;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqliteIndexArtifactTest {
    @TempDir Path root;

    @Test
    void sealed_database_has_real_rows_exact_provenance_and_no_sidecars() throws Exception {
        try (IndexArtifactFixture fixture = new IndexArtifactFixture(root)) {
            fixture.git.commit("README.md", "not Java\n".getBytes(java.nio.charset.StandardCharsets.UTF_8), "non-Java");
            fixture.git.commit("Binary.java", new byte[] {0, 1, 2}, "binary");
            IndexBuildInput input = fixture.prepare("class A {}\n");
            AtomicInteger calls = new AtomicInteger();
            SqliteIndexBuilder builder = new SqliteIndexBuilder(fixture.mapper, (generation, entry, bytes) -> {
                calls.incrementAndGet(); return IndexArtifactFixture.parse(generation, entry, bytes);
            });
            IndexManifest manifest = builder.build(input, fixture.stage(input), () -> { });
            fixture.admit(input, IndexArtifactFixture.request(), Optional.empty());
            SemanticIndexJob running = fixture.jobs.claimNext().orElseThrow();
            Receipt receipt = fixture.indexes.publish(running, fixture.stage(input), manifest);
            assertThat(calls).hasValue(1);
            assertThat(manifest.coverage()).isEqualTo(new Coverage(2, 1, 0, 1, 0, 1, false));
            assertThat(fixture.indexes.selected(input.context())).contains(receipt);
            Path database = fixture.generation(input).resolve("index.sqlite");
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database); Statement statement = connection.createStatement()) {
                try (ResultSet rows = statement.executeQuery("SELECT repository_id,revision,generation,source_manifest_digest,inventory_digest FROM identity")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString(1)).isEqualTo("orders"); assertThat(rows.getString(2)).isEqualTo(input.context().revision());
                    assertThat(rows.getString(3)).isEqualTo(input.generation()); assertThat(rows.getString(4)).isEqualTo(input.sourceManifestDigest());
                    assertThat(rows.getString(5)).isEqualTo(input.sourceManifest().inventoryDigest());
                }
                try (ResultSet rows = statement.executeQuery("SELECT name,declaration_json FROM declarations")) {
                    assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isEqualTo("A");
                    Declaration declaration = fixture.mapper.readValue(rows.getString(2), Declaration.class);
                    assertThat(declaration.contentDigest()).isEqualTo(com.java.semantic.indexer.source.DurableSourceFiles.sha256(
                            "class A {}\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                    assertThat(declaration.symbolId()).isEqualTo(IndexContract.identity(input.generation(), "A.java", "CLASS", "6"));
                    assertThat(rows.next()).isFalse();
                }
                try (ResultSet rows = statement.executeQuery("SELECT path,status FROM files ORDER BY path")) {
                    assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isEqualTo("A.java"); assertThat(rows.getString(2)).isEqualTo("PARSED");
                    assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isEqualTo("Binary.java"); assertThat(rows.getString(2)).isEqualTo("UNSUPPORTED");
                    assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isEqualTo("README.md"); assertThat(rows.getString(2)).isEqualTo("UNSUPPORTED");
                }
            }
            for (String suffix : List.of("-journal", "-wal", "-shm")) assertThat(Files.exists(database.resolveSibling("index.sqlite" + suffix))).isFalse();
            fixture.jobs.complete(fixture.git.repository, new IndexJobId(running.jobId()), receipt);
        }
    }

    @Test
    void source_current_change_does_not_retarget_old_revision_index_and_failed_rebuild_preserves_it() throws Exception {
        try (IndexArtifactFixture fixture = new IndexArtifactFixture(root)) {
            IndexBuildInput first = fixture.prepare("class A {}\n");
            SqliteIndexBuilder builder = new SqliteIndexBuilder(fixture.mapper, IndexArtifactFixture::parse);
            IndexManifest manifest = builder.build(first, fixture.stage(first), () -> { });
            fixture.admit(first, IndexArtifactFixture.request(), Optional.empty());
            SemanticIndexJob running = fixture.jobs.claimNext().orElseThrow();
            Receipt firstReceipt = fixture.indexes.publish(running, fixture.stage(first), manifest);
            fixture.jobs.complete(fixture.git.repository, new IndexJobId(running.jobId()), firstReceipt);
            IndexBuildInput rebuild = fixture.input(first.context());
            fixture.admit(rebuild, IndexArtifactFixture.request(), Optional.of(first.generation()));
            SemanticIndexJob rebuilding = fixture.jobs.claimNext().orElseThrow();
            assertThatThrownBy(() -> builder.build(rebuild, fixture.stage(rebuild), () -> { throw new IllegalStateException("capacity refusal"); }))
                    .isInstanceOf(IllegalStateException.class);
            fixture.jobs.fail(fixture.git.repository, new IndexJobId(rebuilding.jobId()), "CAPACITY_EXCEEDED");
            assertThat(fixture.indexes.selected(first.context())).contains(firstReceipt);
            IndexBuildInput second = fixture.prepare("class A { int value; }\n");
            assertThat(fixture.sources.state(fixture.git.repository).current().orElseThrow().revision()).isEqualTo(second.context().revision());
            assertThat(fixture.indexes.selected(first.context())).contains(firstReceipt);
            assertThat(fixture.indexes.selected(second.context())).isEmpty();
        }
    }

    @Test
    void invalid_parser_name_mapping_and_source_tampering_are_rejected_before_success() throws Exception {
        try (IndexArtifactFixture fixture = new IndexArtifactFixture(root)) {
            IndexBuildInput input = fixture.prepare("class A {}\n");
            SemanticIndexParser invalid = (generation, entry, bytes) -> {
                ParsedFile parsed = IndexArtifactFixture.parse(generation, entry, bytes);
                Declaration valid = parsed.declarations().getFirst();
                Declaration wrong = new Declaration(valid.symbolId(), "B", valid.kind(), valid.owner(), valid.parentSymbolId(), valid.path(),
                        valid.blobId(), valid.contentDigest(), valid.byteLength(), valid.range(), valid.nameRange(), valid.signature());
                return new ParsedFile(parsed.file(), List.of(wrong), List.of(), List.of());
            };
            assertThatThrownBy(() -> new SqliteIndexBuilder(fixture.mapper, invalid).build(input, fixture.stage(input), () -> { }))
                    .isInstanceOf(IndexFailure.class);
            IndexBuildInput changed = fixture.input(input.context());
            Files.writeString(changed.tree().resolve("A.java"), "class B {}\n");
            assertThatThrownBy(() -> new SqliteIndexBuilder(fixture.mapper, IndexArtifactFixture::parse).build(changed, fixture.stage(changed), () -> { }))
                    .isInstanceOf(IndexFailure.class);
            assertThat(fixture.indexes.state(fixture.git.repository).revisions()).isEmpty();
        }
    }

    @Test
    void expected_selection_mismatch_does_not_seal_or_replace_membership() throws Exception {
        try (IndexArtifactFixture fixture = new IndexArtifactFixture(root)) {
            IndexBuildInput input = fixture.prepare("class A {}\n");
            IndexManifest manifest = new SqliteIndexBuilder(fixture.mapper, IndexArtifactFixture::parse).build(input, fixture.stage(input), () -> { });
            fixture.admit(input, IndexArtifactFixture.request(), Optional.of(java.util.UUID.randomUUID().toString()));
            SemanticIndexJob running = fixture.jobs.claimNext().orElseThrow();
            assertThatThrownBy(() -> fixture.indexes.publish(running, fixture.stage(input), manifest)).isInstanceOf(IllegalStateException.class);
            assertThat(fixture.indexes.state(fixture.git.repository).revisions()).isEmpty();
            assertThat(Files.exists(fixture.generation(input))).isFalse();
            assertThat(Files.exists(fixture.stage(input))).isTrue();
        }
    }

    @Test
    void candidate_sql_order_key_uses_evidence_start_and_deferred_parent_keeps_provenance() throws Exception {
        try (IndexArtifactFixture fixture = new IndexArtifactFixture(root)) {
            String source = "class A { public static void main(String[] args) {} }\n";
            IndexBuildInput input = fixture.prepare(source);
            SemanticIndexParser parser = (generation, entry, bytes) -> {
                ParsedFile baseline = IndexArtifactFixture.parse(generation, entry, bytes);
                Declaration type = baseline.declarations().getFirst();
                int nameStart = source.indexOf("main");
                SourceSpan name = new SourceSpan(nameStart, nameStart + 4, 1, 1);
                SourceSpan range = new SourceSpan(source.indexOf("public"), source.indexOf("}", nameStart) + 1, 1, 1);
                Declaration method = new Declaration(IndexContract.identity(generation, entry.path(), "METHOD", Integer.toString(nameStart)),
                        "main", DeclarationKind.METHOD, "A", Optional.of(type.symbolId()), entry.path(), type.blobId(),
                        type.contentDigest(), bytes.length, range, name, "public static void main(String[] args)");
                EntryCandidate candidate = new EntryCandidate(IndexContract.identity(generation, method.symbolId(), "MAIN", Integer.toString(nameStart)),
                        CandidateKind.MAIN, method, new Evidence(EvidenceKind.MAIN_SIGNATURE, Optional.empty(), java.util.Map.of(), List.of(), name));
                // Child first intentionally exercises DEFERRABLE INITIALLY DEFERRED parent FK.
                return new ParsedFile(baseline.file(), List.of(method, type), List.of(candidate), List.of());
            };
            new SqliteIndexBuilder(fixture.mapper, parser).build(input, fixture.stage(input), () -> { });
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + fixture.stage(input).resolve("index.sqlite"));
                    Statement statement = connection.createStatement();
                    ResultSet rows = statement.executeQuery("SELECT candidates.start_byte,declarations.start_byte,candidate_json FROM candidates JOIN declarations USING(symbol_id)")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong(1)).isEqualTo(source.indexOf("main")).isNotEqualTo(rows.getLong(2));
                EntryCandidate candidate = fixture.mapper.readValue(rows.getString(3), EntryCandidate.class);
                assertThat(candidate.evidence().range().startByte()).isEqualTo(rows.getLong(1));
                assertThat(candidate.declaration().parentSymbolId()).isPresent();
            }
        }
    }

    @Test
    void parser_ranges_with_plausible_bytes_but_wrong_raw_line_coordinates_are_rejected() throws Exception {
        try (IndexArtifactFixture fixture = new IndexArtifactFixture(root)) {
            IndexBuildInput input = fixture.prepare("class A {}\n");
            SemanticIndexParser parser = (generation, entry, bytes) -> {
                ParsedFile parsed = IndexArtifactFixture.parse(generation, entry, bytes);
                Declaration valid = parsed.declarations().getFirst();
                Declaration wrong = new Declaration(valid.symbolId(), valid.name(), valid.kind(), valid.owner(), valid.parentSymbolId(),
                        valid.path(), valid.blobId(), valid.contentDigest(), valid.byteLength(),
                        new SourceSpan(0, bytes.length - 1, 1, 2), valid.nameRange(), valid.signature());
                return new ParsedFile(parsed.file(), List.of(wrong), List.of(), List.of());
            };
            assertThatThrownBy(() -> new SqliteIndexBuilder(fixture.mapper, parser).build(input, fixture.stage(input), () -> { }))
                    .isInstanceOf(IndexFailure.class);
            assertThat(Files.exists(fixture.stage(input).resolve("manifest.json"))).isFalse();
        }
    }

    @Test
    void decoded_identifier_is_validated_against_its_complete_raw_unicode_escape() throws Exception {
        try (IndexArtifactFixture fixture = new IndexArtifactFixture(root)) {
            String source = "class " + "\\" + "u0041 {}\n";
            IndexBuildInput input = fixture.prepare(source);
            SemanticIndexParser parser = (generation, entry, bytes) -> {
                Declaration declaration = new Declaration(IndexContract.identity(generation, entry.path(), "CLASS", "6"), "A",
                        DeclarationKind.CLASS, "", Optional.empty(), entry.path(), entry.blobId().orElseThrow(),
                        entry.contentDigest().orElseThrow(), bytes.length, new SourceSpan(0, bytes.length - 1, 1, 1),
                        new SourceSpan(6, 12, 1, 1), "class A");
                return new ParsedFile(new FileOutcome(entry.path(), true, ParseStatus.PARSED, bytes.length,
                        entry.blobId(), entry.contentDigest(), List.of()), List.of(declaration), List.of(), List.of());
            };
            IndexManifest manifest = new SqliteIndexBuilder(fixture.mapper, parser).build(input, fixture.stage(input), () -> { });
            assertThat(manifest.coverage().complete()).isTrue();
            assertThat(manifest.coverage().declarations()).isEqualTo(1);
        }
    }
    @Test
    void fold_with_a_utf8_continuation_boundary_cannot_seal_a_generation() throws Exception {
        try (IndexArtifactFixture fixture = new IndexArtifactFixture(root)) {
            String source = "class A { void run() { String s = \"漢\"; } }\n";
            IndexBuildInput input = fixture.prepare(source);
            SemanticIndexParser invalid = (generation, entry, bytes) -> {
                ParsedFile parsed = new JavaSyntaxExtractor().parse(generation, entry, bytes);
                IndexContract.Fold valid = parsed.folds().getFirst();
                int insideUtf8 = source.substring(0, source.indexOf("漢")).getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 1;
                IndexContract.Fold wrong = new IndexContract.Fold(valid.declarationId(), valid.kind(),
                        new SourceSpan(insideUtf8, insideUtf8 + 1, 1, 1));
                return new ParsedFile(parsed.file(), parsed.declarations(), parsed.candidates(), List.of(wrong));
            };
            assertThatThrownBy(() -> new SqliteIndexBuilder(fixture.mapper, invalid).build(input, fixture.stage(input), () -> { }))
                    .isInstanceOf(IndexFailure.class);
            assertThat(Files.exists(fixture.stage(input).resolve("manifest.json"))).isFalse();
        }
    }

    @Test
    void actual_parser_folds_are_sealed_with_their_declaration_and_raw_interior() throws Exception {
        try (IndexArtifactFixture fixture = new IndexArtifactFixture(root)) {
            String source = "class A {\r\n void run() {\r\n  System.out.println(\"漢\");\r\n }\r\n}\r\n";
            IndexBuildInput input = fixture.prepare(source);
            IndexManifest manifest = new SqliteIndexBuilder(fixture.mapper, new JavaSyntaxExtractor()).build(input, fixture.stage(input), () -> { });
            assertThat(manifest.profile().schemaVersion()).isEqualTo(2);
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + fixture.stage(input).resolve("index.sqlite"));
                    Statement statement = connection.createStatement();
                    ResultSet rows = statement.executeQuery("SELECT fold_json,declaration_json FROM folds JOIN declarations USING(symbol_id)")) {
                assertThat(rows.next()).isTrue();
                IndexContract.Fold fold = fixture.mapper.readValue(rows.getString(1), IndexContract.Fold.class);
                Declaration declaration = fixture.mapper.readValue(rows.getString(2), Declaration.class);
                assertThat(fold.declarationId()).isEqualTo(declaration.symbolId());
                byte[] bytes = source.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                assertThat(new String(bytes, Math.toIntExact(fold.interior().startByte()),
                        Math.toIntExact(fold.interior().endByte() - fold.interior().startByte()), java.nio.charset.StandardCharsets.UTF_8))
                        .isEqualTo("\r\n  System.out.println(\"漢\");\r\n ");
                assertThat(rows.next()).isFalse();
            }
        }
    }

    @Test
    void actual_profile_three_build_seals_plan_coverage_and_source_edges_without_private_sidecars() throws Exception {
        try (IndexArtifactFixture fixture = new IndexArtifactFixture(root)) {
            fixture.properties.getRepositories().get("orders").getRelations().setEnabled(true);
            fixture.properties.getRepositories().get("orders").getRelations().setSourceRoots(List.of(""));
            IndexBuildInput input = fixture.prepare("class A { void first() { second(); } void second() {} }\n");
            SemanticIndexJob accepted = fixture.admit(input, IndexArtifactFixture.request(), Optional.empty());
            SemanticIndexJob running = fixture.jobs.claimNext().orElseThrow();
            IndexManifest manifest = new IndexBuildExecutor(fixture.mapper, fixture.inputs, new PlatformIdentity(), fixture.properties)
                    .build(input, running, fixture.stage(input), () -> { });
            assertThat(manifest.profile()).isEqualTo(IndexContract.relationsProfile());
            assertThat(manifest.analysisPlanDigest()).isEqualTo(accepted.analysis().analysisPlanDigest());
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + fixture.stage(input).resolve("index.sqlite"));
                    Statement statement = connection.createStatement()) {
                try (ResultSet rows = statement.executeQuery("SELECT analysis_plan_digest,provenance_json,coverage_json FROM relation_metadata")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString(1)).isEqualTo(manifest.analysisPlanDigest().orElseThrow());
                    RelationContract.Provenance provenance = fixture.mapper.readValue(rows.getString(2), RelationContract.Provenance.class);
                    assertThat(provenance.sourceRoots()).containsExactly("");
                    assertThat(provenance.artifacts()).isEmpty();
                    RelationContract.Coverage coverage = fixture.mapper.readValue(rows.getString(3), RelationContract.Coverage.class);
                    assertThat(coverage.processedFiles()).isEqualTo(1);
                    assertThat(coverage.callOccurrences()).isEqualTo(1);
                    assertThat(coverage.resolvedCallOccurrences()).isEqualTo(1);
                    assertThat(coverage.extractionComplete()).isTrue();
                }
                try (ResultSet rows = statement.executeQuery("SELECT relation_json,d.declaration_json FROM relations r JOIN declarations d ON d.symbol_id=r.target_symbol_id WHERE r.kind='CALL'")) {
                    assertThat(rows.next()).isTrue();
                    Declaration target = fixture.mapper.readValue(rows.getString(2), Declaration.class);
                    assertThat(target.name()).isEqualTo("second");
                    assertThat(rows.getString(1)).contains("STATIC_RESOLVED");
                }
                // Sealing validates the public projection, not merely SQLite FK consistency.
                statement.executeUpdate("UPDATE relations SET start_byte=start_byte+1 WHERE kind='CALL'");
                assertThatThrownBy(() -> SqliteRelationProjection.validate(connection, fixture.mapper,
                        manifest.analysisPlanDigest().orElseThrow())).isInstanceOf(IOException.class);
            }
            try (DirectoryStream<Path> files = Files.newDirectoryStream(fixture.stage(input))) {
                assertThat(files).extracting(path -> path.getFileName().toString()).containsExactlyInAnyOrder("index.sqlite", "manifest.json");
            }
        }
    }

    @Test
    void projection_counts_associations_but_deduplicates_shared_call_occurrence() throws Exception {
        try (IndexArtifactFixture fixture = new IndexArtifactFixture(root)) {
            String source = "class A { void first() { second(); } void second() {} }\n";
            IndexBuildInput input = fixture.prepare(source);
            new SqliteIndexBuilder(fixture.mapper, new JavaSyntaxExtractor()).build(input, fixture.stage(input), () -> { });
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + fixture.stage(input).resolve("index.sqlite"));
                    Statement statement = connection.createStatement()) {
                connection.setAutoCommit(false);
                Declaration first;
                Declaration second;
                try (ResultSet rows = statement.executeQuery("SELECT declaration_json FROM declarations WHERE name='first'")) {
                    assertThat(rows.next()).isTrue();
                    first = fixture.mapper.readValue(rows.getString(1), Declaration.class);
                }
                try (ResultSet rows = statement.executeQuery("SELECT declaration_json FROM declarations WHERE name='second'")) {
                    assertThat(rows.next()).isTrue();
                    second = fixture.mapper.readValue(rows.getString(1), Declaration.class);
                }
                int start = source.indexOf("second()");
                RelationContract.SourceSite site = new RelationContract.SourceSite(first.path(), first.blobId(), first.contentDigest(),
                        first.byteLength(), new SourceSpan(start, start + "second()".length(), 1, 1), Optional.of(first.symbolId()),
                        new RelationContract.Scope(RelationContract.ScopeKind.METHOD, first.range()));
                try (SqliteRelationProjection projection = new SqliteRelationProjection(connection, fixture.mapper, input, () -> { })) {
                    for (Declaration endpoint : List.of(first, second)) {
                        Optional<RelationContract.Target> target = Optional.of(new RelationContract.Target(RelationContract.TargetKind.SOURCE_SYMBOL,
                                Optional.of(new RelationContract.SourceTarget(endpoint, Optional.of("A." + endpoint.name() + "()"))), Optional.empty()));
                        projection.accept(new RelationContract.Fact(RelationContract.factIdentity(input.generation(), RelationContract.Kind.CALL, site, target),
                                RelationContract.Kind.CALL, site, endpoint.equals(second) ? RelationContract.ResolutionState.STATIC_RESOLVED
                                        : RelationContract.ResolutionState.CANDIDATE, target, Optional.empty(), Optional.empty()));
                    }
                    RelationContract.Coverage coverage = projection.coverage();
                    assertThat(coverage.kindCounts()).containsEntry(RelationContract.Kind.CALL, 2L);
                    assertThat(coverage.stateCounts()).containsEntry(RelationContract.ResolutionState.CANDIDATE, 1L)
                            .containsEntry(RelationContract.ResolutionState.STATIC_RESOLVED, 1L);
                    assertThat(coverage.callOccurrences()).isEqualTo(1);
                    assertThat(coverage.resolvedCallOccurrences()).isEqualTo(1);
                    try (ResultSet rows = statement.executeQuery("SELECT count(*) FROM relations")) {
                        assertThat(rows.next()).isTrue();
                        assertThat(rows.getInt(1)).isEqualTo(2);
                    }
                }
                connection.rollback();
            }
        }
    }

    @Test
    void real_sqlite_page_cap_refuses_rebuild_before_sealing_and_preserves_ready() throws Exception {
        try (IndexArtifactFixture fixture = new IndexArtifactFixture(root)) {
            String owner = "A".repeat(700);
            StringBuilder source = new StringBuilder("class " + owner + " {");
            for (int index = 0; index < 40_000; index++) source.append("void m").append(index).append("() {}");
            source.append("}\n");
            IndexBuildInput original = fixture.prepare(source.toString());
            SqliteIndexBuilder baseline = new SqliteIndexBuilder(fixture.mapper, (generation, entry, bytes) -> {
                Declaration type = largeOwnerDeclarations(generation, entry, bytes, owner, false).declarations().getFirst();
                return new ParsedFile(new FileOutcome(entry.path(), true, ParseStatus.PARSED, bytes.length,
                        entry.blobId(), entry.contentDigest(), List.of()), List.of(type), List.of(), List.of());
            });
            IndexManifest manifest = baseline.build(original, fixture.stage(original), () -> { });
            fixture.admit(original, IndexArtifactFixture.request(), Optional.empty());
            SemanticIndexJob running = fixture.jobs.claimNext().orElseThrow();
            Receipt ready = fixture.indexes.publish(running, fixture.stage(original), manifest);
            fixture.jobs.complete(fixture.git.repository, new IndexJobId(running.jobId()), ready);
            IndexBuildInput rebuild = fixture.input(original.context());
            fixture.admit(rebuild, IndexArtifactFixture.request(), Optional.of(original.generation()));
            SemanticIndexJob rebuilding = fixture.jobs.claimNext().orElseThrow();
            SqliteIndexBuilder capped = new SqliteIndexBuilder(fixture.mapper,
                    (generation, entry, bytes) -> largeOwnerDeclarations(generation, entry, bytes, owner, true));
            assertThatThrownBy(() -> capped.build(rebuild, fixture.stage(rebuild), () -> { }))
                    .isInstanceOf(IndexFailure.class).hasRootCauseInstanceOf(java.sql.SQLException.class)
                    .hasStackTraceContaining("SQLITE_FULL");
            assertThat(Files.exists(fixture.stage(rebuild).resolve("manifest.json"))).isFalse();
            assertThat(fixture.indexes.receipt(rebuilding)).isEmpty();
            assertThat(fixture.indexes.selected(original.context())).contains(ready);
            assertThat(Files.size(fixture.stage(rebuild).resolve("index.sqlite"))).isLessThanOrEqualTo(IndexContract.MAX_DATABASE_BYTES);
            fixture.jobs.fail(fixture.git.repository, new IndexJobId(rebuilding.jobId()), "INDEX_UNAVAILABLE");
        }
    }

    private static ParsedFile largeOwnerDeclarations(String generation, com.java.semantic.model.source.SourceInventoryEntry entry,
            byte[] bytes, String owner, boolean includeMethods) {
        String source = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        Declaration type = new Declaration(IndexContract.identity(generation, entry.path(), "CLASS", "6"), owner,
                DeclarationKind.CLASS, "", Optional.empty(), entry.path(), entry.blobId().orElseThrow(),
                entry.contentDigest().orElseThrow(), bytes.length, new SourceSpan(0, bytes.length - 1, 1, 1),
                new SourceSpan(6, 6 + owner.length(), 1, 1), "class " + owner);
        List<Declaration> declarations = new java.util.ArrayList<>();
        declarations.add(type);
        if (includeMethods) {
            int position = source.indexOf("void ");
            while (position >= 0) {
                int nameStart = position + 5;
                int nameEnd = source.indexOf('(', nameStart);
                int end = source.indexOf('}', nameEnd) + 1;
                declarations.add(new Declaration(IndexContract.identity(generation, entry.path(), "METHOD", Integer.toString(nameStart)),
                        source.substring(nameStart, nameEnd), DeclarationKind.METHOD, owner, Optional.of(type.symbolId()),
                        entry.path(), type.blobId(), type.contentDigest(), bytes.length, new SourceSpan(position, end, 1, 1),
                        new SourceSpan(nameStart, nameEnd, 1, 1), source.substring(position, nameEnd + 2)));
                position = source.indexOf("void ", end);
            }
        }
        return new ParsedFile(new FileOutcome(entry.path(), true, ParseStatus.PARSED, bytes.length,
                entry.blobId(), entry.contentDigest(), List.of()), declarations, List.of(), List.of());
    }

}
