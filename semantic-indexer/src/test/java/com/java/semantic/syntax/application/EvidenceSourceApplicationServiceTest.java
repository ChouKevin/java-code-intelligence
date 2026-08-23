package com.java.semantic.syntax.application;

import com.java.semantic.repository.application.RepositoryApplicationService;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.repository.domain.RepositorySourceContainment;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** typed mapper evidence 經 snapshot authority 與共用 range reader 回讀的契約 */
class EvidenceSourceApplicationServiceTest {

    private static final RepositoryId REPOSITORY_ID = RepositoryId.of("orders");
    private static final RepositoryRevision REVISION = RepositoryRevision.ofSha("0".repeat(40));
    private static final String SOURCE_FILE = "src/main/resources/com/example/OrderMapper.xml";

    @Test
    void should_read_exact_statement_and_fragment_elements_from_their_canonical_locations(
            @TempDir Path repositoryRoot) throws IOException {
        String statement = """
                <select id="find">
                    SELECT * FROM orders
                    <where>
                      <if test="id > 0">id = #{id}</if>
                    </where>
                  </select>""";
        String fragment = """
                <sql id="columns">id, name</sql>""";
        String xml = """
                <mapper namespace="com.example.OrderMapper">
                  <select id="find">
                    SELECT * FROM orders
                    <where>
                      <if test="id > 0">id = #{id}</if>
                    </where>
                  </select>
                  <sql id="columns">id, name</sql>
                </mapper>
                """;
        Path source = repositoryRoot.resolve(SOURCE_FILE);
        Files.createDirectories(source.getParent());
        Files.writeString(source, xml);

        SourceRange statementLocation = new SourceRange(SOURCE_FILE, range(1, 2, 6, 11));
        SourceRange fragmentLocation = new SourceRange(
                SOURCE_FILE, range(7, 2, 7, 2 + fragment.length()));
        MapperStatementIdentity statementIdentity = new MapperStatementIdentity(
                new MapperStatementKey("com.example.OrderMapper", "find"),
                SOURCE_FILE,
                Optional.empty(),
                0,
                MapperEvidenceRepresentation.MAPPER_XML_ELEMENT);
        MapperFragmentIdentity fragmentIdentity = new MapperFragmentIdentity(
                "com.example.OrderMapper",
                "columns",
                SOURCE_FILE,
                1,
                MapperEvidenceRepresentation.MAPPER_XML_ELEMENT);
        MapperEvidenceIndex evidenceIndex = new MapperEvidenceIndex(
                List.of(new MapperStatementEvidence(
                        statementIdentity, "select", statementLocation, List.of(), Optional.empty())),
                List.of(new MapperFragmentEvidence(fragmentIdentity, fragmentLocation)));
        RepositorySyntax syntax = new RepositorySyntax(
                List.of(), List.of(), List.of(), Optional.of(evidenceIndex));
        EvidenceSourceApplicationService service = service(repositoryRoot, syntax);
        List<EvidenceCase> evidenceCases = List.of(
                new EvidenceCase(new EvidenceSourceQuery.MapperStatement(statementIdentity), statementLocation, statement),
                new EvidenceCase(new EvidenceSourceQuery.MapperFragment(fragmentIdentity), fragmentLocation, fragment));

        for (EvidenceCase evidenceCase : evidenceCases) {
            EvidenceSourceResult result = service.read(new EvidenceSourceQuery(
                    REPOSITORY_ID, REVISION, evidenceCase.identity()));

            assertThat(result.identity()).isEqualTo(evidenceCase.identity());
            assertThat(result.location()).isEqualTo(evidenceCase.location());
            assertThat(result.segment().location()).isEqualTo(evidenceCase.location());
            assertThat(result.segment().content()).isEqualTo(evidenceCase.content());
            assertThat(result.segment().nextLocation()).isEmpty();
        }
    }

    private static EvidenceSourceApplicationService service(Path repositoryRoot, RepositorySyntax syntax) {
        RepositoryApplicationService repositories = mock(RepositoryApplicationService.class);
        RepositorySnapshot snapshot = new RepositorySnapshot(REPOSITORY_ID, repositoryRoot, REVISION);
        when(repositories.withSnapshot(eq(REPOSITORY_ID), eq(Optional.of(REVISION)), any()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    Function<RepositorySnapshot, EvidenceSourceResult> operation = invocation.getArgument(2);
                    return operation.apply(snapshot);
                });
        RevisionBoundRepositorySyntaxProvider syntaxProvider = ignored -> syntax;
        return new EvidenceSourceApplicationService(
                repositories,
                syntaxProvider,
                new DefaultRevisionPinnedSourceRangeReader(new RepositorySourceContainment()));
    }

    private static SyntaxRange range(int startLine, int startCharacter, int endLine, int endCharacter) {
        return new SyntaxRange(
                new SyntaxPosition(startLine, startCharacter),
                new SyntaxPosition(endLine, endCharacter));
    }

    private record EvidenceCase(
            EvidenceSourceQuery.EvidenceIdentity identity,
            SourceRange location,
            String content) {
    }
}
