package com.java.semantic.syntax.application;

import com.java.semantic.syntax.domain.SourceMethodMetadata;

import com.java.semantic.config.ExactContentProperties;
import com.java.semantic.identity.JavaTypeIdentity;
import com.java.semantic.identity.MethodTarget;
import com.java.semantic.identity.SourceTypeIdentity;
import com.java.semantic.repository.application.RepositoryApplicationService;
import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.repository.domain.RepositoryStatus;
import com.java.semantic.semantic.domain.SemanticBindingAmbiguousException;
import com.java.semantic.semantic.domain.SemanticTargetNotFoundException;
import com.java.semantic.syntax.adapter.jdt.JdtSyntaxExtractionService;
import com.java.semantic.syntax.domain.AnalysisTargetStatus;
import com.java.semantic.syntax.domain.CanonicalMethodDeclarationResolver;
import com.java.semantic.syntax.domain.SourceTypeMetadata;
import com.java.semantic.syntax.domain.SourceTypeMembers;
import com.java.semantic.syntax.domain.MapperEvidenceIndex;
import com.java.semantic.syntax.domain.MapperEvidenceRepresentation;
import com.java.semantic.syntax.domain.MapperFragmentEvidence;
import com.java.semantic.syntax.domain.MapperFragmentIdentity;
import com.java.semantic.syntax.domain.MapperStatementEvidence;
import com.java.semantic.syntax.domain.MapperStatementIdentity;
import com.java.semantic.syntax.domain.MethodTargetResolution;
import com.java.semantic.syntax.domain.RepositorySyntax;
import com.java.semantic.syntax.domain.SyntaxExtractionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 驗證固定 revision 的 exact source 與 mapper 證據讀取 */
class ExactContentApplicationServiceTest {

    private static final RepositoryId REPOSITORY_ID = new RepositoryId("orders");
    private static final RepositoryRevision REVISION = new RepositoryRevision("0123456789012345678901234567890123456789");
    private static final Path MULTI_MODULE_DATA_ACCESS_FIXTURE =
            Path.of("src/test/resources/fixtures/multi-module-data-access");

    @TempDir
    Path repositoryRoot;

    @Test
    void should_return_only_the_resolved_method_signature_source_for_the_complete_method_target() throws IOException {
        RepositorySyntax syntax = extract("""
                package com.example;
                class Orders {
                    String find(String id) { return id; }
                }
                """);
        MethodTarget target = methodTarget(syntax);

        ExactContentResult result = service(syntax, new ExactContentProperties(32768, 32768))
                .retrieve(new ExactContentQuery.MethodSource(REPOSITORY_ID, REVISION, target));

        assertThat(result.variants()).singleElement().satisfies(variant -> {
            assertThat(variant.statementIdentity()).isEmpty();
            assertThat(variant.fragmentIdentity()).isEmpty();
            assertThat(variant.content().inlineContent()).contains("String find(String id) { return id; }");
        });
        assertThat(ExactContentQuery.MethodSource.class.getRecordComponents())
                .extracting(component -> component.getName())
                .containsExactly("repositoryId", "expectedRevision", "target");
    }

    @Test
    void should_preserve_the_existing_typed_ambiguity_for_distinct_canonical_targets() throws IOException {
        RepositorySyntax extracted = extract("""
                package com.example;
                class Orders {
                    String find(String id) { return id; }
                }
                """);
        SourceTypeMetadata metadata = extracted.sourceTypes().getFirst();
        MethodTarget target = methodTarget(extracted);
        MethodTarget alternate = new MethodTarget(
                new SourceTypeIdentity(
                        new JavaTypeIdentity(target.packageName(), target.className()),
                        "src/main/java/com/example/Alternate.java"),
                target.methodName(),
                target.parameterTypes());
        MethodTargetResolution ambiguous = new MethodTargetResolution(
                AnalysisTargetStatus.AMBIGUOUS,
                Optional.empty(),
                List.of(alternate, target),
                "DUPLICATE_FQN_PROOF");
        RepositorySyntax syntax = new RepositorySyntax(List.of(), List.of(withResolution(metadata, ambiguous)));

        assertThatThrownBy(() -> service(syntax, new ExactContentProperties(32768, 32768))
                .retrieve(new ExactContentQuery.MethodSource(REPOSITORY_ID, REVISION, target)))
                .isInstanceOfSatisfying(SemanticBindingAmbiguousException.class, exception ->
                        assertThat(exception.candidates()).containsExactly(alternate, target));
    }

    @Test
    void should_return_every_extracted_mapper_statement_variant_for_the_exact_logical_identity() {
        RepositorySyntax syntax = new JdtSyntaxExtractionService().extract(MULTI_MODULE_DATA_ACCESS_FIXTURE);
        String namespace = "com.example.persistence.OrderMapper";
        MethodTarget target = syntax.sourceTypes().stream()
                .filter(metadata -> namespace.equals(metadata.declaration().identity().fullyQualifiedName()))
                .flatMap(metadata -> metadata.members().methods().stream())
                .filter(method -> "findByOrderNo".equals(method.name()))
                .map(method -> method.analysisTarget().target().orElseThrow())
                .findFirst()
                .orElseThrow();

        assertThat(syntax.mapperEvidenceIndex().orElseThrow().statements(namespace, target.methodName()))
                .extracting(MapperStatementEvidence::mappedMethodTarget)
                .containsExactly(Optional.empty(), Optional.of(target));

        ExactContentResult result = service(syntax, new ExactContentProperties(32768, 32768))
                .retrieve(new ExactContentQuery.MapperStatement(REPOSITORY_ID, REVISION, target));

        assertThat(result.variants())
                .extracting(variant -> variant.statementIdentity().orElseThrow().representation())
                .containsExactly(
                        MapperEvidenceRepresentation.MAPPER_XML_ELEMENT,
                        MapperEvidenceRepresentation.ANNOTATION_SQL_TEXT);
        assertThat(result.variants()).extracting(variant -> variant.content().inlineContent().orElseThrow())
                .satisfiesExactly(
                        content -> assertThat(content).contains("SELECT xml_marker"),
                        content -> assertThat(content).isEqualTo("SELECT * FROM orders WHERE order_no = #{orderNo}"));
    }

    @Test
    void should_reject_direct_and_segment_sql_lookup_for_overloaded_mapper_methods() throws IOException {
        RepositorySyntax extracted = extract("""
                package com.example;
                interface OrderMapper {
                    String find(String id);
                    String find(Integer id);
                }
                """);
        MethodTarget target = extracted.sourceTypes().getFirst().members().methods().getFirst()
                .analysisTarget().target().orElseThrow();
        MapperStatementIdentity statementIdentity = new MapperStatementIdentity(
                "com.example.OrderMapper", "find", "mapper/OrderMapper.xml", Optional.empty(), 0,
                MapperEvidenceRepresentation.MAPPER_XML_ELEMENT);
        RepositorySyntax syntax = withEvidence(extracted, List.of(new MapperStatementEvidence(
                statementIdentity, "select", "SELECT 1", List.of(), Optional.of(target))), List.of());
        ExactContentApplicationService exactContent = service(syntax, new ExactContentProperties(1024, 1024));
        ExactContentQuery.MapperStatement query = new ExactContentQuery.MapperStatement(REPOSITORY_ID, REVISION, target);

        assertThatThrownBy(() -> exactContent.retrieve(query)).isInstanceOf(SemanticTargetNotFoundException.class);
        assertThatThrownBy(() -> exactContent.readSegment(new ExactContentSegmentQuery(query, "ref", 0)))
                .isInstanceOf(SemanticTargetNotFoundException.class);
    }

    @Test
    void should_reject_direct_and_segment_sql_lookup_for_partially_unresolved_mapper_methods() throws IOException {
        RepositorySyntax extracted = extract("""
                package com.example;
                interface OrderMapper {
                    String find(String id);
                    String find(MissingRequest request);
                }
                """);
        MethodTarget target = extracted.sourceTypes().getFirst().members().methods().getFirst()
                .analysisTarget().target().orElseThrow();
        MapperStatementIdentity statementIdentity = new MapperStatementIdentity(
                "com.example.OrderMapper", "find", "mapper/OrderMapper.xml", Optional.empty(), 0,
                MapperEvidenceRepresentation.MAPPER_XML_ELEMENT);
        RepositorySyntax syntax = withEvidence(extracted, List.of(new MapperStatementEvidence(
                statementIdentity, "select", "SELECT 1", List.of(), Optional.of(target))), List.of());
        ExactContentApplicationService exactContent = service(syntax, new ExactContentProperties(1024, 1024));
        ExactContentQuery.MapperStatement query = new ExactContentQuery.MapperStatement(REPOSITORY_ID, REVISION, target);

        assertThatThrownBy(() -> exactContent.retrieve(query)).isInstanceOf(SemanticTargetNotFoundException.class);
        assertThatThrownBy(() -> exactContent.readSegment(new ExactContentSegmentQuery(query, "ref", 0)))
                .isInstanceOf(SemanticTargetNotFoundException.class);
    }

    @Test
    void should_resolve_statement_includes_from_typed_evidence_without_losing_ambiguous_candidates()
            throws IOException {
        RepositorySyntax extracted = extract("""
                package com.example;
                class Orders {
                    String find(String id) { return id; }
                }
                """);
        MethodTarget target = methodTarget(extracted);
        MapperStatementIdentity statementIdentity = new MapperStatementIdentity(
                "com.example.Orders",
                "find",
                "src/main/resources/mapper/Orders.xml",
                Optional.empty(),
                4,
                MapperEvidenceRepresentation.MAPPER_XML_ELEMENT);
        MapperStatementEvidence statement = new MapperStatementEvidence(
                statementIdentity,
                "select",
                "<select id=\"find\"><include refid=\"unique\"/></select>",
                List.of("unique", "shared", "missing"),
                Optional.of(target));
        MapperFragmentIdentity unique = new MapperFragmentIdentity(
                "com.example.Orders",
                "unique",
                "src/main/resources/mapper/Orders.xml",
                0,
                MapperEvidenceRepresentation.MAPPER_XML_ELEMENT);
        MapperFragmentIdentity sharedSecond = new MapperFragmentIdentity(
                "com.example.Orders",
                "shared",
                "src/main/resources/mapper/z/Shared.xml",
                1,
                MapperEvidenceRepresentation.MAPPER_XML_ELEMENT);
        MapperFragmentIdentity sharedFirst = new MapperFragmentIdentity(
                "com.example.Orders",
                "shared",
                "src/main/resources/mapper/a/Shared.xml",
                9,
                MapperEvidenceRepresentation.MAPPER_XML_ELEMENT);
        RepositorySyntax syntax = withEvidence(
                extracted,
                List.of(statement),
                List.of(
                        new MapperFragmentEvidence(sharedSecond, "<sql id=\"shared\">z</sql>"),
                        new MapperFragmentEvidence(unique, "<sql id=\"unique\">id</sql>"),
                        new MapperFragmentEvidence(sharedFirst, "<sql id=\"shared\">a</sql>")));

        ExactContentResult result = service(syntax, new ExactContentProperties(32768, 32768))
                .retrieve(new ExactContentQuery.MapperStatement(REPOSITORY_ID, REVISION, target));

        assertThat(result.variants()).singleElement().satisfies(variant ->
                assertThat(variant.includeResolutions()).satisfiesExactly(
                        resolution -> {
                            assertThat(resolution.refId()).isEqualTo("unique");
                            assertThat(resolution.status())
                                    .isEqualTo(ExactContentResult.IncludeResolutionStatus.RESOLVED);
                            assertThat(resolution.fragmentIdentities()).containsExactly(unique);
                        },
                        resolution -> {
                            assertThat(resolution.refId()).isEqualTo("shared");
                            assertThat(resolution.status())
                                    .isEqualTo(ExactContentResult.IncludeResolutionStatus.AMBIGUOUS);
                            assertThat(resolution.fragmentIdentities())
                                    .containsExactly(sharedFirst, sharedSecond);
                        },
                        resolution -> {
                            assertThat(resolution.refId()).isEqualTo("missing");
                            assertThat(resolution.status())
                                    .isEqualTo(ExactContentResult.IncludeResolutionStatus.UNRESOLVED);
                            assertThat(resolution.fragmentIdentities()).isEmpty();
                        }));
    }

    @Test
    void should_return_exact_xml_for_the_requested_mapper_fragment_identity() throws IOException {
        RepositorySyntax extracted = extract("""
                package com.example;
                class Orders { }
                """);
        MapperFragmentIdentity fragment = new MapperFragmentIdentity(
                "com.example.OrdersMapper", "baseColumns", "orders.xml", 2,
                MapperEvidenceRepresentation.MAPPER_XML_ELEMENT);
        RepositorySyntax syntax = withEvidence(extracted, List.of(), List.of(
                new MapperFragmentEvidence(fragment, "<sql id=\"baseColumns\">id, status</sql>")));

        ExactContentResult result = service(syntax, new ExactContentProperties(32768, 32768))
                .retrieve(new ExactContentQuery.MapperFragment(REPOSITORY_ID, REVISION, fragment));

        assertThat(result.variants()).singleElement().satisfies(variant -> {
            assertThat(variant.fragmentIdentity()).contains(fragment);
            assertThat(variant.content().inlineContent()).contains("<sql id=\"baseColumns\">id, status</sql>");
        });
    }

    @Test
    void should_fail_with_a_typed_not_found_exception_when_exact_content_is_absent() throws IOException {
        RepositorySyntax syntax = extract("""
                package com.example;
                class Orders { }
                """);
        MethodTarget missing = new MethodTarget(
                new SourceTypeIdentity(
                        new JavaTypeIdentity("com.example", "Orders"),
                        "src/main/java/com/example/Orders.java"),
                "find",
                List.of("java.lang.String"));

        assertThatThrownBy(() -> service(syntax, new ExactContentProperties(32768, 32768))
                .retrieve(new ExactContentQuery.MethodSource(REPOSITORY_ID, REVISION, missing)))
                .isInstanceOf(SemanticTargetNotFoundException.class);
    }

    @Test
    void should_return_oversize_content_by_stable_reference_without_inline_truncation() throws IOException {
        String content = "a".repeat(1025);
        MapperFragmentIdentity firstFragment = fragmentIdentity("largeOne", 0);
        MapperFragmentIdentity secondFragment = fragmentIdentity("largeTwo", 1);
        RepositorySyntax syntax = withEvidence(emptySyntax(), List.of(), List.of(
                new MapperFragmentEvidence(firstFragment, content),
                new MapperFragmentEvidence(secondFragment, content)));
        ExactContentApplicationService service = service(syntax, new ExactContentProperties(1024, 1024));
        ExactContentQuery.MapperFragment firstQuery = new ExactContentQuery.MapperFragment(
                REPOSITORY_ID, REVISION, firstFragment);
        ExactContentQuery.MapperFragment secondQuery = new ExactContentQuery.MapperFragment(
                REPOSITORY_ID, REVISION, secondFragment);

        ExactContentResult.Content first = service.retrieve(firstQuery).variants().getFirst().content();
        ExactContentResult.Content second = service.retrieve(secondQuery).variants().getFirst().content();

        assertThat(first.inlineContent()).isEmpty();
        assertThat(first.contentRef()).isPresent().isEqualTo(second.contentRef());
        assertThat(first.utf8ByteCount()).isEqualTo(1025);
        assertThat(first.segmentCount()).isEqualTo(2);
    }

    @Test
    void should_read_deterministic_unicode_code_point_safe_segments() throws IOException {
        String content = "a".repeat(1023) + "🙂b";
        MapperFragmentIdentity fragment = fragmentIdentity("unicode", 0);
        RepositorySyntax syntax = withEvidence(emptySyntax(), List.of(), List.of(new MapperFragmentEvidence(fragment, content)));
        ExactContentApplicationService service = service(syntax, new ExactContentProperties(1024, 1024));
        ExactContentQuery.MapperFragment query = new ExactContentQuery.MapperFragment(REPOSITORY_ID, REVISION, fragment);
        ExactContentResult.Content oversized = service.retrieve(query).variants().getFirst().content();
        String contentRef = oversized.contentRef().orElseThrow();

        ExactContentSegment first = service.readSegment(new ExactContentSegmentQuery(query, contentRef, 0));
        ExactContentSegment second = service.readSegment(new ExactContentSegmentQuery(query, contentRef, 1));

        assertThat(first.content()).isEqualTo("a".repeat(1023));
        assertThat(first.nextSegmentQuery()).contains(new ExactContentSegmentQuery(query, contentRef, 1));
        assertThat(second.content()).isEqualTo("🙂b");
        assertThat(second.nextSegmentQuery()).isEmpty();
    }

    @Test
    void should_fail_closed_when_inline_and_oversize_content_has_the_same_reference() throws IOException {
        MapperFragmentIdentity first = fragmentIdentity("first", 0);
        MapperFragmentIdentity second = fragmentIdentity("second", 1);
        RepositorySyntax syntax = withEvidence(emptySyntax(), List.of(), List.of(
                new MapperFragmentEvidence(first, "inline"),
                new MapperFragmentEvidence(second, "b".repeat(1025))));

        assertThatThrownBy(() -> service(syntax, new ExactContentProperties(1024, 1024), content -> "collision")
                .retrieve(new ExactContentQuery.MapperFragment(REPOSITORY_ID, REVISION, first)))
                .isInstanceOf(ExactContentApplicationService.ContentReferenceCollisionException.class);
    }

    @Test
    void should_classify_exact_duplicate_mapper_identity_as_internal_contract_failure() {
        MapperFragmentIdentity identity = fragmentIdentity("duplicate", 0);
        MapperFragmentEvidence evidence =
                new MapperFragmentEvidence(identity, "<sql id=\"duplicate\">id</sql>");

        assertThatThrownBy(() -> new MapperEvidenceIndex(
                List.of(),
                List.of(evidence, evidence)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("duplicate mapper fragment identity");
    }

    private RepositorySyntax extract(String source) throws IOException {
        Path sourceFile = repositoryRoot.resolve("src/main/java/com/example/Orders.java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, source);
        return new JdtSyntaxExtractionService().extract(repositoryRoot);
    }

    private ExactContentApplicationService service(RepositorySyntax syntax, ExactContentProperties properties) {
        RepositorySnapshot snapshot = new RepositorySnapshot(REPOSITORY_ID, repositoryRoot, REVISION);
        RepositoryApplicationService repositoryApplicationService = repositoryApplicationService(snapshot);
        SyntaxExtractionService syntaxExtractionService = root -> syntax;
        return new ExactContentApplicationService(
                repositoryApplicationService, syntaxExtractionService, new CanonicalMethodDeclarationResolver(), properties);
    }

    private ExactContentApplicationService service(
            RepositorySyntax syntax,
            ExactContentProperties properties,
            Function<String, String> contentReferenceFactory) {
        RepositorySnapshot snapshot = new RepositorySnapshot(REPOSITORY_ID, repositoryRoot, REVISION);
        RepositoryApplicationService repositoryApplicationService = repositoryApplicationService(snapshot);
        SyntaxExtractionService syntaxExtractionService = root -> syntax;
        return new ExactContentApplicationService(
                repositoryApplicationService,
                syntaxExtractionService,
                new CanonicalMethodDeclarationResolver(),
                properties,
                contentReferenceFactory);
    }

    private RepositoryApplicationService repositoryApplicationService(RepositorySnapshot snapshot) {
        return new RepositoryApplicationService() {
            @Override
            public RepositoryStatus ensure(RepositoryId repositoryId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public RepositoryStatus sync(RepositoryId repositoryId, Optional<String> branch) {
                throw new UnsupportedOperationException();
            }

            @Override
            public RepositoryStatus checkout(RepositoryId repositoryId, String revision) {
                throw new UnsupportedOperationException();
            }

            @Override
            public RepositoryStatus status(RepositoryId repositoryId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<RepositoryStatus> list() {
                throw new UnsupportedOperationException();
            }

            @Override
            public <T> T withSnapshot(
                    RepositoryId repositoryId,
                    Optional<RepositoryRevision> expectedRevision,
                    Function<RepositorySnapshot, T> operation) {
                return operation.apply(snapshot);
            }
        };
    }

    private MethodTarget methodTarget(RepositorySyntax syntax) {
        return syntax.sourceTypes().stream()
                .flatMap(metadata -> metadata.members().methods().stream())
                .map(method -> method.analysisTarget().target().orElseThrow())
                .findFirst()
                .orElseThrow();
    }

    private RepositorySyntax withEvidence(
            RepositorySyntax syntax,
            List<MapperStatementEvidence> statements,
            List<MapperFragmentEvidence> fragments) {
        return new RepositorySyntax(
                syntax.entryPoints(),
                syntax.sourceTypes(),
                syntax.extractionOutcomes(),
                Optional.of(new MapperEvidenceIndex(statements, fragments)));
    }

    private RepositorySyntax emptySyntax() {
        return new RepositorySyntax(List.of(), List.of());
    }

    private MapperFragmentIdentity fragmentIdentity(String fragmentId, int documentOrdinal) {
        return new MapperFragmentIdentity(
                "com.example.OrdersMapper",
                fragmentId,
                "orders.xml",
                documentOrdinal,
                MapperEvidenceRepresentation.MAPPER_XML_ELEMENT);
    }

    private SourceTypeMetadata withResolution(SourceTypeMetadata metadata, MethodTargetResolution resolution) {
        SourceMethodMetadata method = metadata.members().methods().getFirst();
        SourceMethodMetadata replacement = new SourceMethodMetadata(
                method.name(), method.paramTypes(), method.sql(), method.sqlSource(),
                method.startLine(), method.endLine(), method.range(), method.source(),
                method.parameterTypeReferences(), method.returnType(), method.invocations(), method.annotationEvidence(),
                method.bodyTypeReferences(), method.namePosition(), resolution,
                method.executableDeclaration(), method.abstractDeclaration(), method.overridableDeclaration());
        return new SourceTypeMetadata(metadata.declaration(), metadata.relationships(),
                new SourceTypeMembers(metadata.members().fields(), List.of(replacement),
                        metadata.members().fluentSetters(), metadata.members().chainedAccessors()),
                metadata.frameworkFacts(), metadata.compilationUnit());
    }
}
