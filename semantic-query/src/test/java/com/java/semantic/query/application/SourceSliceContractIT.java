package com.java.semantic.query.application;

import com.java.semantic.model.codefact.CodeFactIdentity;
import com.java.semantic.model.codefact.CodeFactId;
import com.java.semantic.model.codefact.ExternalTarget;
import com.java.semantic.model.codefact.MapperStatementKind;
import com.java.semantic.model.codefact.RelationKind;
import com.java.semantic.model.codefact.RelationTarget;
import com.java.semantic.model.codefact.SourceRange;
import com.java.semantic.model.codefact.SyntaxPosition;
import com.java.semantic.model.codefact.SyntaxRange;
import com.java.semantic.query.application.ReadContextSelector.AdmittedContext;
import com.java.semantic.query.application.SemanticQueryContract.*;
import com.java.semantic.query.config.ConfiguredReadPolicy;
import com.java.semantic.query.config.ReadPolicyProperties;
import com.mongodb.client.MongoClients;
import org.bson.Document;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("mongo-it")
class SourceSliceContractIT extends PublishedMongoITSupport {
    private static final String PATH = "src/main/java/example/payment/PaymentClient.java";

    @Test
    void exact_relation_fence_and_neighboring_context_keep_original_utf16_range() {
        try (PublishedMongoLifecycle lifecycle = PublishedMongoLifecycle.start();
                PublishedMongoLifecycle.Invocation invocation = lifecycle.openInvocation()) {
            MongoTemplate template = invocation.template();
            seedCurrent(template, "orders");
            seedSource(template, PATH, "before\r\n  😀charge(); tail\r\nafter\r\nlast\n");
            SyntaxRange range = new SyntaxRange(new SyntaxPosition(1, 2), new SyntaxPosition(1, 13));
            CodeFactIdentity relation = relation(template, range);
            GitEvidenceReadService reader = reader(template, policy());
            AdmittedContext admitted = admitted(template, policy());
            SourceResult exact = reader.readSource(admitted, request(relation, 0, 200, Optional.empty()));
            SourceResult expanded = reader.readSource(admitted, request(relation, 1, 200, Optional.empty()));
            assertThat(exact.content()).contains("😀charge();");
            assertThat(exact.factRange()).contains(range);
            assertThat(exact.pageRange()).contains(range);
            assertThat(exact.rangeComplete()).isTrue();
            assertThat(exact.startLineComplete()).isFalse();
            assertThat(exact.endLineComplete()).isFalse();
            assertThat(expanded.content()).contains("before\r\n  😀charge(); tail\r\nafter\r\n");
            assertThat(expanded.factRange()).contains(range);
            assertThat(expanded.window().orElseThrow().start()).isEqualTo(new SyntaxPosition(0, 0));
            assertThat(expanded.window().orElseThrow().end()).contains(new SyntaxPosition(3, 0));
        }
    }

    @Test
    void long_fact_pages_reconstruct_utf8_without_losing_exclusive_fence_or_partial_line_flags() {
        try (PublishedMongoLifecycle lifecycle = PublishedMongoLifecycle.start();
                PublishedMongoLifecycle.Invocation invocation = lifecycle.openInvocation()) {
            MongoTemplate template = invocation.template();
            seedCurrent(template, "orders");
            String wanted = "😀".repeat(20_000);
            seedSource(template, PATH, "prefix" + wanted + "SECRET_AFTER_FENCE");
            SyntaxRange range = new SyntaxRange(new SyntaxPosition(0, 6), new SyntaxPosition(0, 40_006));
            CodeFactIdentity relation = relation(template, range);
            GitEvidenceReadService reader = reader(template, policy());
            AdmittedContext admitted = admitted(template, policy());
            SourceResult first = reader.readSource(admitted, request(relation, 0, 500, Optional.empty()));
            assertThat(first.content().orElseThrow().getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isEqualTo(65_536);
            assertThat(first.rangeComplete()).isFalse();
            assertThat(first.endLineComplete()).isFalse();
            SourceResult second = reader.readSource(admitted, request(relation, 0, 500, first.nextCursor()));
            assertThat(first.content().orElseThrow() + second.content().orElseThrow()).isEqualTo(wanted);
            assertThat(second.startLineComplete()).isFalse();
            assertThat(second.endLineComplete()).isFalse();
            assertThat(second.rangeComplete()).isTrue();
            assertThat(second.factRange()).contains(range);
            assertThat(second.window()).isEqualTo(first.window());
            assertThat(second.pageRange().orElseThrow().end()).isEqualTo(range.end());
            assertThat(second.nextCursor()).isEmpty();
            assertThatThrownBy(() -> reader.readSource(admitted, request(relation, 1, 500, first.nextCursor())))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void line_limited_fact_pages_do_not_leak_following_source() {
        try (PublishedMongoLifecycle lifecycle = PublishedMongoLifecycle.start();
                PublishedMongoLifecycle.Invocation invocation = lifecycle.openInvocation()) {
            MongoTemplate template = invocation.template();
            seedCurrent(template, "orders");
            String wanted = "line\r\n".repeat(600);
            seedSource(template, PATH, wanted + "outside\n");
            SyntaxRange range = new SyntaxRange(new SyntaxPosition(0, 0), new SyntaxPosition(600, 0));
            CodeFactIdentity relation = relation(template, range);
            GitEvidenceReadService reader = reader(template, policy());
            AdmittedContext admitted = admitted(template, policy());
            SourceResult first = reader.readSource(admitted, request(relation, 0, 500, Optional.empty()));
            SourceResult last = reader.readSource(admitted, request(relation, 0, 500, first.nextCursor()));
            assertThat(first.content()).contains("line\r\n".repeat(500));
            assertThat(last.content()).contains("line\r\n".repeat(100));
            assertThat(last.rangeComplete()).isTrue();
            assertThat(last.pageRange().orElseThrow().end()).isEqualTo(range.end());
        }
    }

    @Test
    void malformed_surrogate_and_crlf_coordinates_fail_instead_of_widening_source() {
        try (PublishedMongoLifecycle lifecycle = PublishedMongoLifecycle.start();
                PublishedMongoLifecycle.Invocation invocation = lifecycle.openInvocation()) {
            MongoTemplate template = invocation.template();
            seedCurrent(template, "orders");
            seedSource(template, PATH, "😀a\r\nnext\n");
            CodeFactIdentity relation = relation(template, new SyntaxRange(new SyntaxPosition(0, 1), new SyntaxPosition(0, 3)));
            GitEvidenceReadService reader = reader(template, policy());
            AdmittedContext admitted = admitted(template, policy());
            assertThatThrownBy(() -> reader.readSource(admitted, request(relation, 0, 200, Optional.empty())))
                    .isInstanceOf(IndexContractMismatchException.class);
            CodeFactIdentity crlf = relation(template, new SyntaxRange(new SyntaxPosition(0, 3), new SyntaxPosition(0, 4)));
            assertThatThrownBy(() -> reader.readSource(admitted, request(crlf, 0, 200, Optional.empty())))
                    .isInstanceOf(IndexContractMismatchException.class);
        }
    }

    @Test
    void fact_read_does_not_require_git_allowlist_but_rejects_other_forbidden_symbols_in_file() {
        try (PublishedMongoLifecycle lifecycle = PublishedMongoLifecycle.start();
                PublishedMongoLifecycle.Invocation invocation = lifecycle.openInvocation()) {
            MongoTemplate template = invocation.template();
            seedCurrent(template, "orders");
            seedSource(template, PATH, "visible();\nforbidden();\n");
            CodeFactIdentity visible = methodIdentity("example.payment", "PaymentClient", "visible", PATH);
            CodeFactIdentity forbidden = methodIdentity("example.payment", "PaymentClient", "forbidden", PATH);
            seedMethod(template, visible, List.of());
            seedMethod(template, forbidden, List.of());
            CodeFactIdentity relation = relation(template, new SyntaxRange(new SyntaxPosition(0, 0), new SyntaxPosition(0, 10)));
            assertThat(reader(template, policy()).readSource(admitted(template, policy()), request(relation, 0, 200, Optional.empty())).content())
                    .contains("visible();");
            ConfiguredReadPolicy denied = new ConfiguredReadPolicy(new ReadPolicyProperties(List.of(), List.of(), List.of(),
                    List.of(new ReadPolicyProperties.MethodRule("orders", "example.payment", "PaymentClient", "forbidden",
                            List.of("example.events.VideoReady")))));
            assertThatThrownBy(() -> reader(template, denied).readSource(admitted(template, denied), request(relation, 0, 200, Optional.empty())))
                    .isInstanceOf(RepositoryNotFoundException.class);
        }
    }

    @Test
    void mapper_fact_reads_authoritative_range_and_sources_projection_is_required() {
        try (PublishedMongoLifecycle lifecycle = PublishedMongoLifecycle.start();
                PublishedMongoLifecycle.Invocation invocation = lifecycle.openInvocation()) {
            MongoTemplate template = invocation.template();
            seedCurrent(template, "orders");
            String path = "src/main/resources/VideoMapper.xml";
            com.java.semantic.model.index.SourceArtifactDocument artifact = seedSource(template, path, "<mapper><select id=\"find\">SELECT 1</select></mapper>");
            CodeFactIdentity mapper = seedMapper(template, path, artifact.id(), MapperStatementKind.SELECT);
            seedSearch(template, mapper, "SYMBOLS", List.of("find"));
            assertThat(reader(template, policy()).readSource(admitted(template, policy()), request(mapper, 0, 200, Optional.empty())).content())
                    .contains("<mapper>");
            template.getCollection("generation_manifests").updateOne(new Document("generationId", "g1"),
                    new Document("$set", new Document("projectionVersions.$[projection].version", 1)),
                    new com.mongodb.client.model.UpdateOptions().arrayFilters(List.of(new Document("projection.name", "SOURCES"))));
            assertThatThrownBy(() -> admitted(template, policy())).isInstanceOf(IndexContractMismatchException.class);
        }
    }

    @Test
    void zero_width_authoritative_fact_returns_an_empty_complete_range_without_widening() {
        try (PublishedMongoLifecycle lifecycle = PublishedMongoLifecycle.start();
                PublishedMongoLifecycle.Invocation invocation = lifecycle.openInvocation()) {
            MongoTemplate template = invocation.template();
            seedCurrent(template, "orders");
            seedSource(template, PATH, "😀tail\n");
            SyntaxPosition point = new SyntaxPosition(0, 2);
            SyntaxRange range = new SyntaxRange(point, point);
            CodeFactIdentity relation = relation(template, range);
            SourceResult result = reader(template, policy()).readSource(admitted(template, policy()), request(relation, 0, 200, Optional.empty()));
            assertThat(result.content()).contains("");
            assertThat(result.factRange()).contains(range);
            assertThat(result.pageRange()).contains(range);
            assertThat(result.rangeComplete()).isTrue();
            assertThat(result.nextCursor()).isEmpty();
        }
    }

    @Test
    void zero_width_fact_at_eof_preserves_exact_range_with_and_without_terminal_newline() {
        try (PublishedMongoLifecycle lifecycle = PublishedMongoLifecycle.start()) {
            for (String content : List.of("class C {}\n", "class C {}")) {
                try (PublishedMongoLifecycle.Invocation invocation = lifecycle.openInvocation()) {
                    MongoTemplate template = invocation.template();
                    seedCurrent(template, "orders");
                    seedSource(template, PATH, content);
                    SyntaxPosition point = content.endsWith("\n") ? new SyntaxPosition(1, 0) : new SyntaxPosition(0, content.length());
                    SyntaxRange range = new SyntaxRange(point, point);
                    CodeFactIdentity identity = relation(template, range);
                    SourceResult result = reader(template, policy()).readSource(admitted(template, policy()), request(identity, 0, 200, Optional.empty()));
                    assertThat(result.content()).contains("");
                    assertThat(result.factRange()).contains(range);
                    assertThat(result.pageRange()).contains(range);
                    assertThat(result.rangeComplete()).isTrue();
                    assertThat(result.nextCursor()).isEmpty();
                }
            }
        }
    }

    @Test
    void shared_artifact_body_does_not_authorize_rebound_snapshot_membership() {
        try (PublishedMongoLifecycle lifecycle = PublishedMongoLifecycle.start();
                PublishedMongoLifecycle.Invocation invocation = lifecycle.openInvocation()) {
            MongoTemplate template = invocation.template();
            seedCurrent(template, "orders");
            seedSource(template, PATH, "visible();\n");
            CodeFactIdentity relation = relation(template, new SyntaxRange(new SyntaxPosition(0, 0), new SyntaxPosition(0, 10)));
            AdmittedContext admitted = admitted(template, policy());
            Document unrelated = new Document(template.getCollection("generation_files").find(new Document("repoId", "orders")).first());
            unrelated.remove("_id");
            unrelated.put("repoId", "other");
            unrelated.put("generationId", "foreign-generation");
            template.getCollection("generation_files").insertOne(unrelated);
            template.getCollection("git_snapshot_files").updateOne(new Document("snapshotId", SOURCE_SNAPSHOT).append("path", PATH),
                    new Document("$set", new Document("checksum", "f".repeat(64))));
            assertThatThrownBy(() -> reader(template, policy()).readSource(admitted, request(relation, 0, 200, Optional.empty())))
                    .isInstanceOf(IndexContractMismatchException.class);
        }
    }

    @Test
    void whole_file_admission_rejects_forbidden_type_and_nonmapper_mapper_payload() {
        try (PublishedMongoLifecycle lifecycle = PublishedMongoLifecycle.start();
                PublishedMongoLifecycle.Invocation invocation = lifecycle.openInvocation()) {
            MongoTemplate template = invocation.template();
            seedCurrent(template, "orders");
            com.java.semantic.model.index.SourceArtifactDocument artifact = seedSource(template, PATH, "visible();\nmethod();\n");
            CodeFactIdentity relation = relation(template, new SyntaxRange(new SyntaxPosition(0, 0), new SyntaxPosition(0, 10)));
            com.java.semantic.model.codefact.SourceTypeIdentity hidden = new com.java.semantic.model.codefact.SourceTypeIdentity(
                    new com.java.semantic.model.codefact.JavaTypeIdentity("example.payment", "HiddenType"), PATH);
            seedType(template, hidden, artifact.id());
            ConfiguredReadPolicy denied = new ConfiguredReadPolicy(new ReadPolicyProperties(List.of(), List.of(),
                    List.of(new ReadPolicyProperties.ClassRule("orders", "example.payment", "HiddenType")), List.of()));
            assertThatThrownBy(() -> reader(template, denied).readSource(admitted(template, denied), request(relation, 0, 200, Optional.empty())))
                    .isInstanceOf(RepositoryNotFoundException.class);
            template.getCollection("symbols").updateOne(new Document("kind", "TYPE"),
                    new Document("$set", new Document("mapperStatementKind", "SELECT")));
            assertThatThrownBy(() -> reader(template, policy()).readSource(admitted(template, policy()), request(relation, 0, 200, Optional.empty())))
                    .isInstanceOf(IndexContractMismatchException.class);
        }
    }

    @Test
    void hidden_missing_unpublished_and_stale_repositories_are_denied_before_storage_reads() {
        try (PublishedMongoLifecycle lifecycle = PublishedMongoLifecycle.start();
                PublishedMongoLifecycle.Invocation invocation = lifecycle.openInvocation()) {
            MongoTemplate template = invocation.template();
            seedCurrent(template, "orders");
            java.util.List<String> reads = new java.util.concurrent.CopyOnWriteArrayList<>();
            com.mongodb.event.CommandListener listener = new com.mongodb.event.CommandListener() {
                @Override public void commandStarted(com.mongodb.event.CommandStartedEvent event) {
                    if ("find".equals(event.getCommandName()) || "aggregate".equals(event.getCommandName())) reads.add(event.getCommandName());
                }
            };
            com.mongodb.MongoClientSettings settings = com.mongodb.MongoClientSettings.builder()
                    .applyConnectionString(new com.mongodb.ConnectionString(lifecycle.connectionString())).addCommandListener(listener).build();
            try (com.mongodb.client.MongoClient client = MongoClients.create(settings)) {
                MongoTemplate observed = new MongoTemplate(client, invocation.databaseName());
                ConfiguredReadPolicy hidden = new ConfiguredReadPolicy(new ReadPolicyProperties(
                        List.of("orders", "missing"), List.of(), List.of(), List.of()));
                ReadContextSelector selector = new ReadContextSelector(selector(observed, hidden),
                        new ReviewManifestReadService(observed, hidden, Duration.ofSeconds(2)), guard(observed, hidden), hidden);
                for (ReadContext context : List.of(ReadContext.current("missing", REVISION), ReadContext.current("orders", REVISION),
                        ReadContext.current("orders", "3".repeat(40)))) {
                    assertThatThrownBy(() -> selector.select(context, SelectedGenerationGuard.SEARCH_WITH_SOURCES, ReadContextSelector.Access.SEMANTIC))
                            .isInstanceOf(RepositoryNotFoundException.class);
                }
                template.getCollection("repositories").updateOne(new Document("repoId", "orders"), new Document("$unset", new Document("currentPointer", "")));
                assertThatThrownBy(() -> selector.select(ReadContext.current("orders", REVISION),
                        SelectedGenerationGuard.SEARCH_WITH_SOURCES, ReadContextSelector.Access.SEMANTIC)).isInstanceOf(RepositoryNotFoundException.class);
                assertThat(reads).isEmpty();
            }
        }
    }

    private static CodeFactIdentity relation(MongoTemplate template, SyntaxRange range) {
        CodeFactIdentity from = methodIdentity("example.payment", "PaymentClient", "visible", PATH);
        CodeFactIdentity identity = seedRelation(template, from, RelationKind.CALLS_OUTBOUND_API,
                new RelationTarget.External(new ExternalTarget.Endpoint("POST", "https://payments.example/charge")),
                new SourceRange(PATH, range)).fact().identity();
        seedSearch(template, identity, "RELATIONS", List.of("visible"));
        return identity;
    }
    private static SourceRequest request(CodeFactIdentity identity, int contextLines, int maxLines, Optional<String> cursor) {
        return new SourceRequest(ReadContext.current("orders", REVISION), new SourceTarget(SourceTargetKind.FACT,
                Optional.of(CodeFactId.from(identity).value()), Optional.empty(), Optional.empty(), Optional.of(contextLines)), maxLines, cursor);
    }
    private static AdmittedContext admitted(MongoTemplate template, ConfiguredReadPolicy policy) {
        return new ReadContextSelector(selector(template, policy), new ReviewManifestReadService(template, policy, Duration.ofSeconds(2)),
                guard(template, policy), policy).select(ReadContext.current("orders", REVISION), SelectedGenerationGuard.SEARCH_WITH_SOURCES,
                        ReadContextSelector.Access.SEMANTIC);
    }
    private static GitEvidenceReadService reader(MongoTemplate template, ConfiguredReadPolicy policy) {
        return new GitEvidenceReadService(template, policy, Duration.ofSeconds(2), new CodeFactReadService(template, guard(template, policy), Duration.ofSeconds(2)));
    }
}
