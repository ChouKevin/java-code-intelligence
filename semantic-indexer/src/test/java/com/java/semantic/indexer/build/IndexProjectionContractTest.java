package com.java.semantic.indexer.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.java.semantic.model.codefact.CodeFactKind;
import com.java.semantic.model.codefact.ExternalTarget;
import com.java.semantic.model.codefact.RelationKind;
import com.java.semantic.model.codefact.RelationTarget;
import com.java.semantic.model.index.SourceArtifactDocument;
import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IndexProjectionContractTest {

    @TempDir
    Path repository;

    @Test
    void source_batch_keeps_documents_bound_to_one_authoritative_source_and_content_artifact() {
        SourceArtifactDocument artifact = SourceArtifactDocument.create("class Invoice {}\r\n// \uD83D\uDE00\r\n");
        SourceIndexBatch batch = new SourceIndexBatch(new RepositoryId("invoices"), new GenerationId("invoice-generation"),
                "src/main/java/example/Invoice.java", 0, artifact, Optional.empty(), List.of(), List.of(), List.of(), List.of());

        assertEquals("src/main/java/example/Invoice.java", batch.sourcePath());
        assertEquals(List.of(0, 18, 25), batch.sourceArtifact().lineOffsets());
        assertTrue(batch.symbols().isEmpty());
        assertTrue(batch.relations().isEmpty());
        assertTrue(batch.entryPoints().isEmpty());
        assertFalse(batch.search().stream().anyMatch(document -> document.normalizedTokens().contains("payment")));
    }

    @Test
    void projects_mapper_statements_as_owned_symbols() throws Exception {
        Path javaSource = repository.resolve("src/main/java/example/OrderMapper.java");
        Path mapper = repository.resolve("src/main/resources/mapper/OrderMapper.xml");
        Files.createDirectories(javaSource.getParent());
        Files.createDirectories(mapper.getParent());
        Files.writeString(javaSource, "package example; interface OrderMapper { void find(); }\n");
        Files.writeString(mapper, "<mapper namespace=\"example.OrderMapper\"><select id=\"find\">select 1</select></mapper>");

        java.util.List<SourceIndexBatch> batches = new JdtLsRepositoryIndexExporter().export(new RepositoryId("orders"),
                new RepositoryRevision("a".repeat(40)), new GenerationId("g1"), new FullIndexPlanner().plan(repository));

        assertTrue(batches.stream().flatMap(batch -> batch.symbols().stream())
                .anyMatch(symbol -> symbol.kind() == com.java.semantic.model.codefact.CodeFactKind.MAPPER_STATEMENT
                        && "find".equals(symbol.name())));
        assertTrue(batches.stream().filter(batch -> batch.sourcePath().equals("src/main/resources/mapper/OrderMapper.xml"))
                .allMatch(batch -> batch.extractionIssue().isEmpty()));
    }

    @Test
    void projects_owned_enum_constants_record_components_and_crlf_utf16_source_ranges() throws Exception {
        Path javaSource = repository.resolve("src/main/java/example/Coordinates.java");
        Files.createDirectories(javaSource.getParent());
        String source = "package example;\r\n"
                + "enum PaymentMethod { CARD, CASH }\r\n"
                + "record Receipt(String emoji) {}\r\n"
                + "class Coordinates { String \uD801\uDC00note = \"😀\"; }\r\n";
        Files.writeString(javaSource, source);

        java.util.List<SourceIndexBatch> batches = new JdtLsRepositoryIndexExporter().export(new RepositoryId("payment-service"),
                new RepositoryRevision("b".repeat(40)), new GenerationId("g2"), new FullIndexPlanner().plan(repository));

        SourceIndexBatch batch = batches.getFirst();
        assertTrue(batch.symbols().stream().anyMatch(symbol -> symbol.kind() == com.java.semantic.model.codefact.CodeFactKind.ENUM_CONSTANT
                && "CARD".equals(symbol.name()) && symbol.range().range().start().line() == 1));
        assertTrue(batch.symbols().stream().anyMatch(symbol -> symbol.kind() == com.java.semantic.model.codefact.CodeFactKind.RECORD_COMPONENT
                && "emoji".equals(symbol.name()) && symbol.range().range().start().line() == 2));
        assertTrue(batch.symbols().stream().anyMatch(symbol -> symbol.kind() == com.java.semantic.model.codefact.CodeFactKind.FIELD
                && "\uD801\uDC00note".equals(symbol.name()) && symbol.range().range().start().line() == 3
                && symbol.range().range().start().character() == source.substring(source.lastIndexOf("\r\n", source.indexOf("Coordinates")) + 2)
                        .indexOf("\uD801\uDC00note")));
        assertTrue(batch.search().stream().filter(document -> document.packageName().isPresent())
                .allMatch(document -> "example".equals(document.packageName().orElseThrow())));
        assertEquals(List.of(0, source.indexOf("\r\n") + 2, source.indexOf("\r\n", source.indexOf("\r\n") + 2) + 2,
                        source.indexOf("\r\n", source.indexOf("\r\n", source.indexOf("\r\n") + 2) + 2) + 2,
                        source.length()),
                batch.sourceArtifact().lineOffsets());
    }

    @Test
    void projects_explicit_configuration_and_error_contract_evidence_without_name_guesses() throws Exception {
        Path javaSource = repository.resolve("src/main/java/example/ConfiguredClient.java");
        Files.createDirectories(javaSource.getParent());
        Files.writeString(javaSource, """
                package example;
                import org.springframework.beans.factory.annotation.Value;
                class ConfiguredClient {
                    @Value("${remote.catalog.base-url}")
                    String baseUrl;
                    void fetch() throws CatalogUnavailableException, java.io.IOException { }
                }
                class CatalogUnavailableException extends RuntimeException { }
                """);

        java.util.List<SourceIndexBatch> batches = new JdtLsRepositoryIndexExporter().export(new RepositoryId("catalog"),
                new RepositoryRevision("c".repeat(40)), new GenerationId("g3"), new FullIndexPlanner().plan(repository));

        assertTrue(batches.stream().flatMap(batch -> batch.relations().stream())
                .anyMatch(relation -> relation.kind() == com.java.semantic.model.codefact.RelationKind.READS_CONFIGURATION
                        && relation.target().canonicalForm().contains("remote.catalog.base-url")));
        assertTrue(batches.stream().flatMap(batch -> batch.relations().stream())
                .anyMatch(relation -> relation.kind() == RelationKind.DECLARES_ERROR_CONTRACT
                        && relation.target() instanceof RelationTarget.Internal internal
                        && internal.identity().kind() == CodeFactKind.TYPE
                        && internal.identity().canonicalForm().contains("example.CatalogUnavailableException")
                        && relation.range().range().start().line() == 5
                        && relation.range().range().start().character() == 24));
        assertTrue(batches.stream().flatMap(batch -> batch.relations().stream())
                .anyMatch(relation -> relation.kind() == RelationKind.DECLARES_ERROR_CONTRACT
                        && relation.target() instanceof RelationTarget.External external
                        && external.target().equals(new ExternalTarget.NominalType(
                                new com.java.semantic.model.codefact.DeclaredType("java.io.IOException")))
                        && relation.range().range().start().line() == 5
                        && relation.range().range().start().character() == 53));
    }

    @Test
    void carries_the_real_syntax_failure_outcome_into_its_source_batch() throws Exception {
        Path source = repository.resolve("src/main/java/example/Broken.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package example; class Broken {");

        java.util.List<SourceIndexBatch> batches = new JdtLsRepositoryIndexExporter().export(new RepositoryId("orders"),
                new RepositoryRevision("a".repeat(40)), new GenerationId("g1"), new FullIndexPlanner().plan(repository));

        assertEquals(1, batches.size());
        assertEquals("JDT_SYNTAX_PROBLEM", batches.getFirst().extractionIssue().orElseThrow().code());
        assertTrue(batches.getFirst().symbols().isEmpty());
        assertFalse(batches.getFirst().sourceScope().usableScopes());
    }
}
