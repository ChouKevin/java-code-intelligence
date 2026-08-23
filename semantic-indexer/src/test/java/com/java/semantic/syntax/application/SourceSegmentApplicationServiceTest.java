package com.java.semantic.syntax.application;

import com.java.semantic.repository.application.RepositoryApplicationService;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.repository.domain.RepositorySourceContainment;
import com.java.semantic.syntax.adapter.jdt.JdtSyntaxExtractionService;
import com.java.semantic.syntax.domain.MapperEvidenceIndex;
import com.java.semantic.syntax.domain.MapperEvidenceRepresentation;
import com.java.semantic.syntax.domain.MapperFragmentEvidence;
import com.java.semantic.syntax.domain.MapperFragmentIdentity;
import com.java.semantic.syntax.domain.MapperStatementEvidence;
import com.java.semantic.syntax.domain.MapperStatementIdentity;
import com.java.semantic.syntax.domain.MapperStatementKey;
import com.java.semantic.syntax.domain.RepositorySyntax;
import com.java.semantic.syntax.domain.RevisionBoundRepositorySyntaxProvider;
import com.java.semantic.model.codefact.SourceRange;
import com.java.semantic.model.codefact.SyntaxPosition;
import com.java.semantic.model.codefact.SyntaxRange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** revision-bound canonical source segment 的內容與 byte bound 契約 */
class SourceSegmentApplicationServiceTest {

    private static final RepositoryId REPOSITORY_ID = RepositoryId.of("orders");
    private static final RepositoryRevision REVISION = RepositoryRevision.ofSha("0".repeat(40));
    private static final String SOURCE_FILE = "src/main/java/com/acme/Order.java";

    @Test
    void should_preserve_expanded_context_across_byte_bounded_continuations(@TempDir Path repositoryRoot)
            throws IOException {
        String largePrefix = "x".repeat(70_000);
        String largeSuffix = "y".repeat(70_000);
        String content = """
                class Order {
                  void method() {
                    // %s
                    // first
                    // target α
                    // last
                    // %s
                  }
                }
                """.formatted(largePrefix, largeSuffix).replace("\n", "\r\n");
        write(repositoryRoot, content);
        SourceSegmentApplicationService service = service(repositoryRoot);
        SourceSegmentQuery query = query(range(4, 7, 4, 15), 2);

        SourceSegmentResult result = service.read(query);

        assertThat(result.location().range().start()).isEqualTo(new SyntaxPosition(2, 0));
        assertThat(result.contextTruncated()).isFalse();
        List<String> segments = new ArrayList<>();
        while (true) {
            assertThat(result.content().getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(64 * 1024);
            segments.add(result.content());
            if (result.nextLocation().isEmpty()) {
                break;
            }
            SourceRange nextLocation = result.nextLocation().orElseThrow();
            assertThat(nextLocation.range().start()).isEqualTo(result.location().range().end());
            result = service.read(new SourceSegmentQuery(REPOSITORY_ID, REVISION, nextLocation, 0));
        }

        String expected = content.substring(content.indexOf("    // " + largePrefix), content.indexOf("\r\n  }"));
        assertThat(String.join("", segments)).isEqualTo(expected);
        assertThat(result.location().range().end())
                .isEqualTo(new SyntaxPosition(6, largeSuffix.length() + 7));
        assertThat(result.repositoryId()).isEqualTo(REPOSITORY_ID);
        assertThat(result.analyzedRevision()).isEqualTo(REVISION);
    }

    @Test
    void should_clip_java_context_and_every_continuation_to_the_containing_type(@TempDir Path repositoryRoot)
            throws IOException {
        String unauthorizedPrefix = "outside-type-".repeat(6_000);
        String authorizedContent = "inside-type-".repeat(6_000);
        String content = "class Outer {\n"
                + "  // " + unauthorizedPrefix + "\n"
                + "  class Inner {\n"
                + "    // " + authorizedContent + "\n"
                + "    void target() {}\n"
                + "  }\n"
                + "}\n";
        write(repositoryRoot, content);
        SourceSegmentApplicationService service = service(repositoryRoot);

        SourceSegmentResult result = service.read(query(range(4, 9, 4, 15), 20));
        List<String> segments = new ArrayList<>();
        while (true) {
            assertThat(result.content().getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(64 * 1024);
            segments.add(result.content());
            if (result.nextLocation().isEmpty()) {
                break;
            }
            SourceRange nextLocation = result.nextLocation().orElseThrow();
            assertThat(nextLocation.sourceFile()).isEqualTo(SOURCE_FILE);
            assertThat(nextLocation.range().start()).isEqualTo(result.location().range().end());
            result = service.read(new SourceSegmentQuery(REPOSITORY_ID, REVISION, nextLocation, 0));
        }

        String joined = String.join("", segments);
        assertThat(joined).startsWith("class Inner {");
        assertThat(joined).endsWith("  }");
        assertThat(joined).contains(authorizedContent);
        assertThat(joined).doesNotContain(unauthorizedPrefix).doesNotContain("class Outer");
    }

    @Test
    void should_return_the_first_segment_when_an_exact_range_exceeds_the_utf8_byte_cap(
            @TempDir Path repositoryRoot) throws IOException {
        String content = "class Order { String value() { return \"" + "界".repeat(22_000) + "\"; } }";
        write(repositoryRoot, content);
        SourceSegmentApplicationService service = service(repositoryRoot);

        SourceSegmentResult result = service.read(query(range(0, 0, 0, content.length()), 0));
        List<String> segments = new ArrayList<>();
        while (true) {
            assertThat(result.content()).isNotEmpty();
            assertThat(result.content().getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(64 * 1024);
            assertThat(Character.isLowSurrogate(result.content().charAt(result.content().length() - 1))).isFalse();
            segments.add(result.content());
            if (result.nextLocation().isEmpty()) {
                break;
            }
            SourceRange nextLocation = result.nextLocation().orElseThrow();
            assertThat(nextLocation.range().start().character())
                    .isGreaterThan(result.location().range().start().character());
            result = service.read(new SourceSegmentQuery(REPOSITORY_ID, REVISION, nextLocation, 0));
        }

        assertThat(String.join("", segments)).isEqualTo(content);
    }

    @Test
    void should_report_missing_or_unsafe_source_as_exact_content_not_found(@TempDir Path repositoryRoot)
            throws IOException {
        Path outside = Files.writeString(repositoryRoot.getParent().resolve("outside.java"), "class Outside {}\n");
        Path link = repositoryRoot.resolve(SOURCE_FILE);
        Files.createDirectories(link.getParent());
        Files.createSymbolicLink(link, outside);
        SourceSegmentApplicationService service = service(repositoryRoot);

        assertThatThrownBy(() -> service.read(query(range(0, 0, 0, 1), 0)))
                .isInstanceOf(SourceSegmentNotFoundException.class);
    }

    @Test
    void should_continue_dynamic_mapper_statement_and_fragment_evidence(@TempDir Path repositoryRoot)
            throws IOException {
        String statementPath = "src/main/resources/com/acme/OrderMapper.xml";
        String fragmentPath = "src/main/resources/com/acme/OrderFragments.xml";
        String statement = "<select id=\"find\"><if test=\"active\">"
                + "界".repeat(22_000)
                + "</if></select>";
        String fragment = "<sql id=\"columns\"><if test=\"includeDetails\">"
                + "界".repeat(22_000)
                + "</if></sql>";
        SourceRange statementLocation = new SourceRange(statementPath, range(1, 2, 1, 2 + statement.length()));
        SourceRange fragmentLocation = new SourceRange(fragmentPath, range(1, 2, 1, 2 + fragment.length()));
        write(repositoryRoot, statementPath, "<mapper>\n  " + statement + "\n</mapper>\n");
        write(repositoryRoot, fragmentPath, "<mapper>\n  " + fragment + "\n</mapper>\n");
        MapperStatementIdentity statementIdentity = new MapperStatementIdentity(
                new MapperStatementKey("com.acme.OrderMapper", "find"),
                statementPath,
                Optional.empty(),
                0,
                MapperEvidenceRepresentation.MAPPER_XML_ELEMENT);
        MapperFragmentIdentity fragmentIdentity = new MapperFragmentIdentity(
                "com.acme.OrderMapper",
                "columns",
                fragmentPath,
                0,
                MapperEvidenceRepresentation.MAPPER_XML_ELEMENT);
        RepositorySyntax syntax = new RepositorySyntax(
                List.of(),
                List.of(),
                List.of(),
                Optional.of(new MapperEvidenceIndex(
                        List.of(new MapperStatementEvidence(
                                statementIdentity, "select", statementLocation, List.of(), Optional.empty())),
                        List.of(new MapperFragmentEvidence(fragmentIdentity, fragmentLocation)))));
        SourceSegmentApplicationService service = service(repositoryRoot, syntax);

        assertThat(readAll(service, statementLocation)).isEqualTo(statement);
        assertThat(readAll(service, fragmentLocation)).isEqualTo(fragment);
    }

    @Test
    void should_reject_context_expansion_from_a_mapper_subrange(@TempDir Path repositoryRoot)
            throws IOException {
        String mapperPath = "src/main/resources/com/acme/OrderMapper.xml";
        String statement = "<select id=\"find\">select 1</select>";
        String content = """
                <mapper>
                  <sql id="secret">neighboring secret</sql>
                  %s
                </mapper>
                """.formatted(statement);
        SourceRange statementLocation = new SourceRange(mapperPath, range(2, 2, 2, 2 + statement.length()));
        write(repositoryRoot, mapperPath, content);
        MapperStatementIdentity statementIdentity = new MapperStatementIdentity(
                new MapperStatementKey("com.acme.OrderMapper", "find"),
                mapperPath,
                Optional.empty(),
                0,
                MapperEvidenceRepresentation.MAPPER_XML_ELEMENT);
        RepositorySyntax syntax = new RepositorySyntax(
                List.of(),
                List.of(),
                List.of(),
                Optional.of(new MapperEvidenceIndex(
                        List.of(new MapperStatementEvidence(
                                statementIdentity, "select", statementLocation, List.of(), Optional.empty())),
                        List.of())));
        SourceSegmentApplicationService service = service(repositoryRoot, syntax);

        assertThatThrownBy(() -> service.read(query(mapperPath, range(2, 2, 2, 10), 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void should_reject_fabricated_non_evidence_resource_locations(@TempDir Path repositoryRoot)
            throws IOException {
        String mapperPath = "src/main/resources/com/acme/OrderMapper.xml";
        String statement = "<select id=\"find\">select 1</select>";
        SourceRange statementLocation = new SourceRange(mapperPath, range(1, 2, 1, 2 + statement.length()));
        write(repositoryRoot, mapperPath, "<mapper>\n  " + statement + "\n  <select id=\"other\">select 2</select>\n</mapper>\n");
        write(repositoryRoot, "src/main/resources/application.yml", "secret: value\n");
        write(repositoryRoot, "src/main/resources/application.properties", "secret=value\n");
        write(repositoryRoot, "src/main/resources/com/acme/OtherMapper.xml", "<mapper/>\n");
        MapperStatementIdentity statementIdentity = new MapperStatementIdentity(
                new MapperStatementKey("com.acme.OrderMapper", "find"),
                mapperPath,
                Optional.empty(),
                0,
                MapperEvidenceRepresentation.MAPPER_XML_ELEMENT);
        RepositorySyntax syntax = new RepositorySyntax(
                List.of(),
                List.of(),
                List.of(),
                Optional.of(new MapperEvidenceIndex(
                        List.of(new MapperStatementEvidence(
                                statementIdentity, "select", statementLocation, List.of(), Optional.empty())),
                        List.of())));
        SourceSegmentApplicationService service = service(repositoryRoot, syntax);

        assertThatThrownBy(() -> service.read(query(
                "src/main/resources/application.yml", range(0, 0, 0, 13), 0)))
                .isInstanceOf(SourceSegmentNotFoundException.class);
        assertThatThrownBy(() -> service.read(query(
                "src/main/resources/application.properties", range(0, 0, 0, 12), 0)))
                .isInstanceOf(SourceSegmentNotFoundException.class);
        assertThatThrownBy(() -> service.read(query(
                "src/main/resources/com/acme/OtherMapper.xml", range(0, 0, 0, 9), 0)))
                .isInstanceOf(SourceSegmentNotFoundException.class);
        assertThatThrownBy(() -> service.read(query(mapperPath, range(2, 2, 2, 37), 0)))
                .isInstanceOf(SourceSegmentNotFoundException.class);
    }

    private SourceSegmentApplicationService service(Path repositoryRoot) {
        return service(repositoryRoot, new JdtSyntaxExtractionService().extract(repositoryRoot));
    }

    private SourceSegmentApplicationService service(Path repositoryRoot, RepositorySyntax syntax) {
        RepositoryApplicationService repositories = mock(RepositoryApplicationService.class);
        RepositorySnapshot snapshot = new RepositorySnapshot(REPOSITORY_ID, repositoryRoot, REVISION);
        when(repositories.withSnapshot(eq(REPOSITORY_ID), eq(Optional.of(REVISION)), any()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    Function<RepositorySnapshot, SourceSegmentResult> operation = invocation.getArgument(2);
                    return operation.apply(snapshot);
                });
        RevisionBoundRepositorySyntaxProvider syntaxProvider = ignored -> syntax;
        return new SourceSegmentApplicationService(
                repositories,
                syntaxProvider,
                new DefaultRevisionPinnedSourceRangeReader(new RepositorySourceContainment()));
    }

    private SourceSegmentQuery query(SyntaxRange range, int contextLines) {
        return query(SOURCE_FILE, range, contextLines);
    }

    private SourceSegmentQuery query(String sourceFile, SyntaxRange range, int contextLines) {
        return new SourceSegmentQuery(
                REPOSITORY_ID,
                REVISION,
                new SourceRange(sourceFile, range),
                contextLines);
    }

    private String readAll(SourceSegmentApplicationService service, SourceRange location) {
        SourceSegmentResult result = service.read(new SourceSegmentQuery(REPOSITORY_ID, REVISION, location, 0));
        List<String> segments = new ArrayList<>();
        while (true) {
            assertThat(result.content().getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(64 * 1024);
            segments.add(result.content());
            if (result.nextLocation().isEmpty()) {
                return String.join("", segments);
            }
            SourceRange nextLocation = result.nextLocation().orElseThrow();
            assertThat(nextLocation.range().start()).isEqualTo(result.location().range().end());
            result = service.read(new SourceSegmentQuery(REPOSITORY_ID, REVISION, nextLocation, 0));
        }
    }

    private void write(Path repositoryRoot, String content) throws IOException {
        write(repositoryRoot, SOURCE_FILE, content);
    }

    private void write(Path repositoryRoot, String sourceFile, String content) throws IOException {
        Path source = repositoryRoot.resolve(sourceFile);
        Files.createDirectories(source.getParent());
        Files.writeString(source, content);
    }

    private SyntaxRange range(int startLine, int startCharacter, int endLine, int endCharacter) {
        return new SyntaxRange(
                new SyntaxPosition(startLine, startCharacter),
                new SyntaxPosition(endLine, endCharacter));
    }
}
