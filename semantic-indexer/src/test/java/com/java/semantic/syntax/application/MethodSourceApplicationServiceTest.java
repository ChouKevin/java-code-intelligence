package com.java.semantic.syntax.application;

import com.java.semantic.identity.MethodTarget;
import com.java.semantic.repository.application.RepositoryApplicationService;
import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.repository.domain.RepositorySourceContainment;
import com.java.semantic.repository.domain.RepositoryStatus;
import com.java.semantic.syntax.adapter.jdt.JdtSyntaxExtractionService;
import com.java.semantic.syntax.domain.CanonicalMethodDeclarationResolver;
import com.java.semantic.syntax.domain.RepositorySyntax;
import com.java.semantic.syntax.domain.RevisionBoundRepositorySyntaxProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/** 驗證方法完整宣告會在固定 revision 內轉為 Java 原始碼首段 */
class MethodSourceApplicationServiceTest {

    private static final RepositoryId REPOSITORY_ID = RepositoryId.of("orders");
    private static final RepositoryRevision REVISION = RepositoryRevision.fixture();

    @Test
    void should_include_attached_javadoc_in_the_canonical_declaration_location(@TempDir Path repositoryRoot)
            throws IOException {
        RepositorySyntax syntax = extract(repositoryRoot, """
                package com.example;
                class Orders {
                    /** Finds one order */
                    String find(String id) { return id; }
                }
                """);
        MethodTarget target = methodTarget(syntax);

        MethodSourceResult result = service(repositoryRoot, syntax).read(
                new MethodSourceQuery(REPOSITORY_ID, REVISION, target));

        assertThat(result.declarationLocation().range().start().line()).isEqualTo(2);
        assertThat(result.segment().content()).startsWith("/** Finds one order */");
        assertThat(result.segment().content()).endsWith("String find(String id) { return id; }");
        assertThat(result.segment().nextLocation()).isEmpty();
    }

    @Test
    void should_return_a_continuation_for_an_oversized_method_declaration(@TempDir Path repositoryRoot)
            throws IOException {
        String literal = "界".repeat(22_000);
        RepositorySyntax syntax = extract(repositoryRoot, """
                package com.example;
                class Orders {
                    String find() { return \"%s\"; }
                }
                """.formatted(literal));

        MethodSourceResult result = service(repositoryRoot, syntax).read(
                new MethodSourceQuery(REPOSITORY_ID, REVISION, methodTarget(syntax)));

        assertThat(result.segment().content().getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(65_536);
        assertThat(result.segment().nextLocation()).isPresent();
        assertThat(result.segment().contextTruncated()).isFalse();
    }

    private RepositorySyntax extract(Path repositoryRoot, String source) throws IOException {
        Path sourceFile = repositoryRoot.resolve("src/main/java/com/example/Orders.java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, source);
        return new JdtSyntaxExtractionService().extract(repositoryRoot);
    }

    private MethodSourceApplicationService service(Path repositoryRoot, RepositorySyntax syntax) {
        RepositorySnapshot snapshot = new RepositorySnapshot(REPOSITORY_ID, repositoryRoot, REVISION);
        RevisionBoundRepositorySyntaxProvider provider = ignoredSnapshot -> syntax;
        return new MethodSourceApplicationService(
                repositoryApplicationService(snapshot),
                provider,
                new CanonicalMethodDeclarationResolver(),
                new DefaultRevisionPinnedSourceRangeReader(new RepositorySourceContainment()));
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
        return syntax.sourceTypes().getFirst().members().methods().getFirst()
                .analysisTarget().target().orElseThrow();
    }
}
