package com.java.semantic.indexer.build;

import static org.assertj.core.api.Assertions.assertThat;

import com.java.semantic.model.codefact.ExternalTarget;
import com.java.semantic.model.codefact.RelationKind;
import com.java.semantic.model.codefact.RelationTarget;
import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.index.RelationDocument;
import com.java.semantic.model.index.SymbolDocument;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SemanticRelationProjectorFrameworkEvidenceTest {

    @TempDir
    Path repository;

    @Test
    void rejects_local_framework_lookalikes_and_comment_or_string_literals_as_specialized_evidence() throws IOException {
        write("src/main/java/com/example/KafkaTemplate.java", """
                package com.example;

                final class KafkaTemplate {
                    void send(String topic, String body) { }
                }
                """);
        write("src/main/java/com/example/FeignClient.java", """
                package com.example;

                @interface FeignClient { String name(); }
                """);
        write("src/main/java/com/example/PostMapping.java", """
                package com.example;

                @interface PostMapping { String value(); }
                """);
        write("src/main/java/com/example/CustomCatalog.java", """
                package com.example;

                @FeignClient(name = "custom")
                interface CustomCatalog {
                    @PostMapping("/should-not-be-an-endpoint")
                    void create(String id);
                }
                """);
        write("src/main/java/com/example/LookalikeClient.java", """
                package com.example;

                final class LookalikeClient {
                    private final KafkaTemplate kafkaTemplate = new KafkaTemplate();
                    private CustomCatalog customCatalog;

                    void send(String id) {
                        // kafkaTemplate.send("comment.topic", id);
                        String misleading = "customCatalog.create(\\\"string.endpoint\\\")";
                        kafkaTemplate.send("spoof.topic", id);
                        customCatalog.create(id);
                    }
                }
                """);

        List<RelationDocument> relations = exportRelations();

        assertThat(relations).noneMatch(relation -> relation.kind() == RelationKind.PUBLISHES_MESSAGE
                || relation.kind() == RelationKind.CALLS_OUTBOUND_API);
        assertThat(relations).anyMatch(relation -> relation.kind() == RelationKind.CALLS);
    }

    @Test
    void projects_exact_framework_bindings_with_literal_destination_and_typed_feign_endpoint_ranges() throws IOException {
        writeFrameworkContractStubs();
        write("src/main/java/com/example/RemoteCatalog.java", """
                package com.example;

                import org.springframework.cloud.openfeign.FeignClient;
                import org.springframework.web.bind.annotation.PostMapping;

                @FeignClient(name = "catalog")
                interface RemoteCatalog {
                    @PostMapping("/transcoding/jobs")
                    void create(String id);
                }
                """);
        write("src/main/java/com/example/VideoEventPublisher.java", """
                package com.example;

                import org.springframework.kafka.core.KafkaTemplate;

                final class VideoEventPublisher {
                    private final KafkaTemplate<String, String> kafkaTemplate = new KafkaTemplate<>();

                    void publish(String id) {
                        kafkaTemplate.send("video.uploaded", id);
                    }
                }
                """);
        write("src/main/java/com/example/VideoCatalogClient.java", """
                package com.example;

                final class VideoCatalogClient {
                    private RemoteCatalog catalog;

                    void create(String id) {
                        catalog.create(id);
                    }
                }
                """);

        List<RelationDocument> relations = exportRelations();

        RelationDocument destination = relations.stream().filter(relation -> relation.kind() == RelationKind.PUBLISHES_MESSAGE)
                .filter(relation -> relation.target() instanceof RelationTarget.External external
                        && external.target().equals(new ExternalTarget.Destination("kafka", "video.uploaded")))
                .findFirst().orElseThrow();
        assertThat(destination.range().sourceFile()).isEqualTo("src/main/java/com/example/VideoEventPublisher.java");
        assertThat(destination.range().range().start().line()).isEqualTo(8);
        assertThat(destination.range().range().start().character()).isEqualTo(27);
        assertThat(destination.range().range().end().character()).isEqualTo(43);
        RelationDocument endpoint = relations.stream().filter(relation -> relation.kind() == RelationKind.CALLS_OUTBOUND_API)
                .filter(relation -> relation.target() instanceof RelationTarget.External external
                        && external.target().equals(new ExternalTarget.Endpoint("POST", "/transcoding/jobs")))
                .findFirst().orElseThrow();
        assertThat(endpoint.range().sourceFile()).isEqualTo("src/main/java/com/example/VideoCatalogClient.java");
        assertThat(endpoint.range().range().start().line()).isEqualTo(6);
        assertThat(endpoint.range().range().start().character()).isEqualTo(8);
    }

    @Test
    void projects_an_internal_call_to_the_persisted_declaration_identity() throws IOException {
        write("src/main/java/com/example/LocalService.java", """
                package com.example;

                final class LocalService {
                    LocalService() { }

                    void accept(String id) { }
                }
                """);
        write("src/main/java/com/example/Caller.java", """
                package com.example;

                final class Caller {
                    void call() {
                        new LocalService().accept("id");
                    }
                }
                """);

        List<SourceIndexBatch> batches = exportBatches();
        SymbolDocument accept = batches.stream().flatMap(batch -> batch.symbols().stream())
                .filter(symbol -> symbol.signature().equals("com.example.LocalService#accept(java.lang.String)"))
                .findFirst().orElseThrow();
        RelationDocument call = batches.stream().flatMap(batch -> batch.relations().stream())
                .filter(relation -> relation.kind() == RelationKind.CALLS)
                .filter(relation -> relation.range().sourceFile().equals("src/main/java/com/example/Caller.java"))
                .filter(relation -> relation.target() instanceof RelationTarget.Internal)
                .filter(relation -> relation.target().canonicalForm().contains("#accept("))
                .findFirst().orElseThrow();

        RelationTarget.Internal target = (RelationTarget.Internal) call.target();
        assertThat(target.identity()).isEqualTo(accept.fact().identity());
    }

    @Test
    void projects_an_internal_override_to_the_persisted_parent_declaration_identity() throws IOException {
        write("src/main/java/com/example/LocalContract.java", """
                package com.example;

                interface LocalContract {
                    void accept(String id);
                }
                """);
        write("src/main/java/com/example/LocalImplementation.java", """
                package com.example;

                final class LocalImplementation implements LocalContract {
                    @Override
                    public void accept(String id) { }
                }
                """);

        List<SourceIndexBatch> batches = exportBatches();
        SymbolDocument parent = batches.stream().flatMap(batch -> batch.symbols().stream())
                .filter(symbol -> symbol.signature().equals("com.example.LocalContract#accept(java.lang.String)"))
                .findFirst().orElseThrow();
        RelationDocument override = batches.stream().flatMap(batch -> batch.relations().stream())
                .filter(relation -> relation.kind() == RelationKind.OVERRIDES)
                .filter(relation -> relation.range().sourceFile().equals("src/main/java/com/example/LocalImplementation.java"))
                .findFirst().orElseThrow();

        RelationTarget.Internal target = (RelationTarget.Internal) override.target();
        assertThat(target.identity()).isEqualTo(parent.fact().identity());
    }

    private void writeFrameworkContractStubs() throws IOException {
        write("src/main/java/org/springframework/kafka/core/KafkaTemplate.java", """
                package org.springframework.kafka.core;

                public final class KafkaTemplate<K, V> {
                    public void send(String topic, V body) { }
                }
                """);
        write("src/main/java/org/springframework/cloud/openfeign/FeignClient.java", """
                package org.springframework.cloud.openfeign;

                public @interface FeignClient { String name(); }
                """);
        write("src/main/java/org/springframework/web/bind/annotation/PostMapping.java", """
                package org.springframework.web.bind.annotation;

                public @interface PostMapping { String value(); }
                """);
    }

    private List<RelationDocument> exportRelations() {
        return exportBatches().stream().flatMap(batch -> batch.relations().stream()).toList();
    }

    private List<SourceIndexBatch> exportBatches() {
        return new JdtLsRepositoryIndexExporter().export(new RepositoryId("framework-evidence"),
                new RepositoryRevision("a".repeat(40)), new GenerationId("framework-generation"),
                new FullIndexPlanner().plan(repository));
    }

    private void write(String relativePath, String source) throws IOException {
        Path file = repository.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
    }
}
