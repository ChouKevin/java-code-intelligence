package com.java.semantic.syntax.application.concept;
import com.java.semantic.model.codefact.SyntaxPosition;
import com.java.semantic.model.codefact.SyntaxRange;
import com.java.semantic.model.codefact.SourceRange;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import com.java.semantic.model.codefact.JavaTypeIdentity;
import com.java.semantic.model.codefact.MethodTarget;
import com.java.semantic.model.codefact.SourceTypeIdentity;
import com.java.semantic.syntax.application.concept.MapperConceptIdentity.MapperStatementConceptIdentity;
import com.java.semantic.syntax.application.concept.MapperConceptIdentity.MapperStatementVariantEvidenceIdentity;
import com.java.semantic.syntax.adapter.jdt.JdtSyntaxExtractionService;
import com.java.semantic.syntax.domain.MapperEvidenceIndex;
import com.java.semantic.syntax.domain.MapperEvidenceRepresentation;
import com.java.semantic.syntax.domain.MapperStatementEvidence;
import com.java.semantic.syntax.domain.MapperStatementIdentity;
import com.java.semantic.syntax.domain.MapperStatementKey;
import com.java.semantic.syntax.domain.RepositorySyntax;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/** mapper 證據轉為結構化概念的規則 */
class MapperConceptProjectionTest {

    private static final String ANNOTATION_MAPPER = """
            package com.example;

            import org.apache.ibatis.annotations.Select;

            interface OrderMapper {

                @Select("SELECT 訂單名稱 FROM orders WHERE id = #{id}")
                String findOrders(String id);
            }
            """;

    private static final String XML_MAPPER = """
            <?xml version="1.0" encoding="UTF-8" ?>
            <mapper namespace="com.example.OrderMapper">
              <select id="findOrders">SELECT name FROM orders WHERE id = #{id}</select>
            </mapper>
            """;

    private final StructuredConceptCatalogProjector projector = new StructuredConceptCatalogProjector();

    @Test
    void should_place_entry_point_and_mapper_variants_in_final_identity_families()
            throws ClassNotFoundException {
        assertThat(ConceptIdentity.class.getPermittedSubclasses())
                .extracting(Class::getSimpleName)
                .containsExactly(
                        "DeclarationConceptIdentity",
                        "UsageConceptIdentity",
                        "EntryPointConceptIdentity",
                        "MapperConceptIdentity");

        Class<?> entryPointFamily = Class.forName(
                "com.java.semantic.syntax.application.concept.EntryPointConceptIdentity");
        assertThat(entryPointFamily.getPermittedSubclasses())
                .extracting(Class::getSimpleName)
                .containsExactly(
                        "ApiRouteConceptIdentity",
                        "MqDestinationConceptIdentity",
                        "ScheduleConceptIdentity");

        Class<?> mapperFamily = Class.forName(
                "com.java.semantic.syntax.application.concept.MapperConceptIdentity");
        assertThat(mapperFamily.getPermittedSubclasses())
                .extracting(Class::getSimpleName)
                .containsExactly(
                        "MapperStatementConceptIdentity",
                        "MapperStatementVariantEvidenceIdentity");
        Class<?> mapperVariantEvidence = Class.forName(
                "com.java.semantic.syntax.application.concept.MapperConceptIdentity$MapperStatementVariantEvidenceIdentity");
        assertThat(mapperVariantEvidence.getRecordComponents())
                .singleElement()
                .satisfies(component -> assertThat(component.getType()).isEqualTo(MapperStatementIdentity.class));
        Class<?> mapperLogicalIdentity = Class.forName(
                "com.java.semantic.syntax.application.concept.MapperConceptIdentity$MapperStatementConceptIdentity");
        assertThat(mapperLogicalIdentity.getRecordComponents())
                .singleElement()
                .satisfies(component -> assertThat(component.getType()).isEqualTo(MapperStatementKey.class));
    }

    @Test
    void should_aggregate_mapper_variants_by_logical_identity_with_complete_deterministic_evidence() {
        MapperStatementIdentity postgresIdentity = new MapperStatementIdentity(
                new MapperStatementKey("com.example.OrderMapper", "findOrders"),
                "module-a/src/main/resources/mapper/OrderMapper.xml",
                Optional.of("postgres"),
                1,
                MapperEvidenceRepresentation.MAPPER_XML_ELEMENT);
        MapperStatementIdentity oracleIdentity = new MapperStatementIdentity(
                new MapperStatementKey("com.example.OrderMapper", "findOrders"),
                "module-b/src/main/resources/mapper/OrderMapper.xml",
                Optional.of("oracle"),
                0,
                MapperEvidenceRepresentation.MAPPER_XML_ELEMENT);
        MethodTarget mappedMethod = new MethodTarget(
                new SourceTypeIdentity(
                        new JavaTypeIdentity("com.example", "ArchiveGateway"),
                        "src/main/java/com/example/ArchiveGateway.java"),
                "loadVendorRows",
                List.of());
        MapperEvidenceIndex evidenceIndex = new MapperEvidenceIndex(
                List.of(
                        new MapperStatementEvidence(
                                oracleIdentity,
                                "select",
                                location(oracleIdentity.resourcePath()),
                                List.of(),
                                Optional.of(mappedMethod)),
                        new MapperStatementEvidence(
                                postgresIdentity,
                                "select",
                                location(postgresIdentity.resourcePath()),
                                List.of(),
                                Optional.empty())),
                List.of());
        RepositorySyntax syntax = new RepositorySyntax(List.of(), List.of(), List.of(), Optional.of(evidenceIndex));

        StructuredConceptCatalog catalog = projector.project(syntax);

        assertThat(catalog.entries())
                .filteredOn(entry -> entry.kind() == ConceptKind.MAPPER_STATEMENT)
                .singleElement()
                .satisfies(entry -> {
                    MapperStatementConceptIdentity logicalIdentity =
                            new MapperStatementConceptIdentity(
                                    new MapperStatementKey("com.example.OrderMapper", "findOrders"));
                    assertThat(entry.identity()).isEqualTo(logicalIdentity);
                    assertThat(entry.evidence()).containsExactly(
                            logicalIdentity,
                            new MapperStatementVariantEvidenceIdentity(postgresIdentity),
                            new MapperStatementVariantEvidenceIdentity(oracleIdentity));
                    assertThat(entry.mapperStatementMapping()).isPresent().get().satisfies(mapping -> {
                        assertThat(mapping.status())
                                .isEqualTo(MapperStatementMethodMapping.Status.RESOLVED);
                        assertThat(mapping.statementIdentity()).isEqualTo(logicalIdentity);
                        assertThat(mapping.targets()).containsExactly(mappedMethod);
                        assertThat(mapping.reason()).isEmpty();
                    });
                    assertThat(new ConceptSearchDocumentProjector().project(entry).tokens())
                            .contains("order", "mapper", "find", "orders", "archive", "gateway", "load", "vendor", "rows")
                            .doesNotContain("module", "resources", "postgres", "oracle", "secret", "marker", "select");
                });
    }

    @Test
    void should_preserve_mapper_variants_when_delimited_order_keys_would_collide() {
        MapperStatementIdentity pathDelimiterIdentity = new MapperStatementIdentity(
                new MapperStatementKey("com.example.OrderMapper", "findOrders"),
                "src/a.xml|b.xml",
                Optional.of("c"),
                0,
                MapperEvidenceRepresentation.MAPPER_XML_ELEMENT);
        MapperStatementIdentity databaseDelimiterIdentity = new MapperStatementIdentity(
                new MapperStatementKey("com.example.OrderMapper", "findOrders"),
                "src/a.xml",
                Optional.of("b.xml|c"),
                0,
                MapperEvidenceRepresentation.MAPPER_XML_ELEMENT);
        MapperEvidenceIndex evidenceIndex = new MapperEvidenceIndex(
                List.of(
                        new MapperStatementEvidence(
                                pathDelimiterIdentity,
                                "select",
                                location(pathDelimiterIdentity.resourcePath()),
                                List.of(),
                                Optional.empty()),
                        new MapperStatementEvidence(
                                databaseDelimiterIdentity,
                                "select",
                                location(databaseDelimiterIdentity.resourcePath()),
                                List.of(),
                                Optional.empty())),
                List.of());
        RepositorySyntax syntax = new RepositorySyntax(List.of(), List.of(), List.of(), Optional.of(evidenceIndex));

        StructuredConceptCatalog catalog = projector.project(syntax);

        assertThat(catalog.entries())
                .filteredOn(entry -> entry.kind() == ConceptKind.MAPPER_STATEMENT)
                .singleElement()
                .satisfies(entry -> assertThat(entry.evidence())
                        .contains(
                                new MapperStatementVariantEvidenceIdentity(pathDelimiterIdentity),
                                new MapperStatementVariantEvidenceIdentity(databaseDelimiterIdentity)));
    }

    @Test
    void should_not_activate_mapper_statement_concepts_without_an_evidence_index() {
        StructuredConceptCatalog catalog = projector.project(new RepositorySyntax(List.of(), List.of()));

        assertThat(catalog.entries()).extracting(ConceptCatalogEntry::kind)
                .doesNotContain(ConceptKind.MAPPER_STATEMENT);
    }

    @Test
    void should_index_annotation_sql_with_a_distinct_typed_representation(@TempDir Path tempDir) throws IOException {
        Path repositoryRoot = tempDir.resolve("order-service");
        Path sourceFile = repositoryRoot.resolve("src/main/java/com/example/OrderMapper.java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, ANNOTATION_MAPPER);

        RepositorySyntax syntax = new JdtSyntaxExtractionService().extract(repositoryRoot);
        MapperStatementIdentity annotationIdentity = new MapperStatementIdentity(
                new MapperStatementKey("com.example.OrderMapper", "findOrders"),
                "src/main/java/com/example/OrderMapper.java",
                Optional.empty(),
                0,
                MapperEvidenceRepresentation.ANNOTATION_SQL_TEXT);

        assertThat(syntax.mapperEvidenceIndex()).isPresent();
        assertThat(syntax.mapperEvidenceIndex().orElseThrow().statement(annotationIdentity).orElseThrow()
                .location().sourceFile()).isEqualTo(annotationIdentity.resourcePath());
    }

    @Test
    void should_not_index_annotation_sql_when_the_constant_value_is_unavailable(@TempDir Path tempDir)
            throws IOException {
        Path repositoryRoot = tempDir.resolve("order-service");
        Path sourceFile = repositoryRoot.resolve("src/main/java/com/example/OrderMapper.java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, """
                package com.example;

                import org.apache.ibatis.annotations.Select;

                interface OrderMapper {
                    @Select(ExternalSql.QUERY)
                    String findOrders(String id);
                }
                """);

        RepositorySyntax syntax = new JdtSyntaxExtractionService().extract(repositoryRoot);

        assertThat(syntax.mapperEvidenceIndex()).isPresent();
        assertThat(syntax.mapperEvidenceIndex().orElseThrow().statements())
                .extracting(statement -> statement.identity().representation())
                .doesNotContain(MapperEvidenceRepresentation.ANNOTATION_SQL_TEXT);
    }

    @Test
    void should_map_xml_statement_to_complete_method_target_from_extracted_syntax(@TempDir Path tempDir)
            throws IOException {
        Path repositoryRoot = tempDir.resolve("order-service");
        Path sourceFile = repositoryRoot.resolve("src/main/java/com/example/OrderMapper.java");
        Path mapperFile = repositoryRoot.resolve("src/main/resources/mapper/OrderMapper.xml");
        Files.createDirectories(sourceFile.getParent());
        Files.createDirectories(mapperFile.getParent());
        Files.writeString(sourceFile, """
                package com.example;

                interface OrderMapper {
                    String findOrders(String id);
                }
                """);
        Files.writeString(mapperFile, XML_MAPPER);

        StructuredConceptCatalog catalog = projector.project(
                new JdtSyntaxExtractionService().extract(repositoryRoot));

        assertThat(catalog.entries())
                .filteredOn(entry -> entry.kind() == ConceptKind.MAPPER_STATEMENT)
                .singleElement()
                .satisfies(entry -> assertThat(entry.mapperStatementMapping())
                        .isPresent()
                        .get()
                        .satisfies(mapping -> {
                            assertThat(mapping.status())
                                    .isEqualTo(MapperStatementMethodMapping.Status.RESOLVED);
                            assertThat(mapping.targets()).singleElement().satisfies(target -> {
                                assertThat(target.sourceFile())
                                        .isEqualTo("src/main/java/com/example/OrderMapper.java");
                                assertThat(target.packageName()).isEqualTo("com.example");
                                assertThat(target.className()).isEqualTo("OrderMapper");
                                assertThat(target.methodName()).isEqualTo("findOrders");
                                assertThat(target.parameterTypes()).containsExactly("java.lang.String");
                            });
                        }));
    }

    @Test
    void should_mark_mixed_complete_and_incomplete_same_name_declarations_unresolved(@TempDir Path tempDir)
            throws IOException {
        Path repositoryRoot = tempDir.resolve("order-service");
        Path sourceFile = repositoryRoot.resolve("src/main/java/com/example/OrderMapper.java");
        Path mapperFile = repositoryRoot.resolve("src/main/resources/mapper/OrderMapper.xml");
        Files.createDirectories(sourceFile.getParent());
        Files.createDirectories(mapperFile.getParent());
        Files.writeString(sourceFile, """
                package com.example;

                interface OrderMapper {
                    String findOrders(String id);
                    String findOrders(MissingType request);
                }
                """);
        Files.writeString(mapperFile, XML_MAPPER);

        StructuredConceptCatalog catalog = projector.project(
                new JdtSyntaxExtractionService().extract(repositoryRoot));

        assertThat(catalog.entries())
                .filteredOn(entry -> entry.kind() == ConceptKind.MAPPER_STATEMENT)
                .singleElement()
                .satisfies(entry -> assertThat(entry.mapperStatementMapping())
                        .isPresent()
                        .get()
                        .satisfies(mapping -> {
                            assertThat(mapping.status())
                                    .isEqualTo(MapperStatementMethodMapping.Status.UNRESOLVED);
                            assertThat(mapping.reason().map(Enum::name))
                                    .contains("INCOMPLETE_METHOD_RESOLUTION");
                            assertThat(mapping.targets()).singleElement().satisfies(target ->
                                    assertThat(target.parameterTypes()).containsExactly("java.lang.String"));
                        }));
    }
    private static com.java.semantic.model.codefact.SourceRange location(String sourceFile) {
        return new com.java.semantic.model.codefact.SourceRange(
                sourceFile,
                new com.java.semantic.model.codefact.SyntaxRange(
                        new com.java.semantic.model.codefact.SyntaxPosition(0, 0),
                        new com.java.semantic.model.codefact.SyntaxPosition(0, 1)));
    }
}
