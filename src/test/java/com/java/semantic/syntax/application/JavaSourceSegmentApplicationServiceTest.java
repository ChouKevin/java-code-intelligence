package com.java.semantic.syntax.application;

import com.java.semantic.repository.application.RepositoryApplicationService;
import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.repository.domain.RepositorySourceContainment;
import com.java.semantic.syntax.domain.SyntaxPosition;
import com.java.semantic.syntax.domain.SyntaxRange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** revision-bound Java source segment 的內容與 byte bound 契約 */
class JavaSourceSegmentApplicationServiceTest {

    private static final RepositoryId REPOSITORY_ID = RepositoryId.of("orders");
    private static final RepositoryRevision REVISION = RepositoryRevision.fixture();
    private static final String SOURCE_FILE = "src/main/java/com/acme/Order.java";

    @Test
    void should_preserve_source_and_shrink_whole_context_lines_symmetrically(@TempDir Path repositoryRoot)
            throws IOException {
        String largePrefix = "x".repeat(70_000);
        String largeSuffix = "y".repeat(70_000);
        String content = largePrefix + "\r\nfirst\r\ntarget α\r\nlast\r\n" + largeSuffix;
        write(repositoryRoot, content);
        JavaSourceSegmentApplicationService service = service(repositoryRoot);
        JavaSourceSegmentQuery query = query(range(2, 0, 2, 8), 2);

        JavaSourceSegmentResult result = service.read(query);

        assertThat(result.content()).isEqualTo("first\r\ntarget α\r\nlast");
        assertThat(result.contentRange()).isEqualTo(new SourceRange(SOURCE_FILE, range(1, 0, 3, 4)));
        assertThat(result.contextTruncated()).isTrue();
        assertThat(result.returnedUtf8Bytes()).isEqualTo(result.content().getBytes(StandardCharsets.UTF_8).length);
        assertThat(result.requestedRange()).isEqualTo(query.sourceRange());
        assertThat(result.repositoryId()).isEqualTo(REPOSITORY_ID);
        assertThat(result.analyzedRevision()).isEqualTo(REVISION);
    }

    @Test
    void should_reject_an_exact_range_over_the_utf8_byte_cap(@TempDir Path repositoryRoot) throws IOException {
        String content = "界".repeat(22_000);
        write(repositoryRoot, content);
        JavaSourceSegmentApplicationService service = service(repositoryRoot);

        assertThatThrownBy(() -> service.read(query(range(0, 0, 0, content.length()), 0)))
                .isInstanceOf(SourceSegmentTooLargeException.class);
    }

    @Test
    void should_report_missing_or_unsafe_source_as_exact_content_not_found(@TempDir Path repositoryRoot)
            throws IOException {
        Path outside = Files.writeString(repositoryRoot.getParent().resolve("outside.java"), "class Outside {}\n");
        Path link = repositoryRoot.resolve(SOURCE_FILE);
        Files.createDirectories(link.getParent());
        Files.createSymbolicLink(link, outside);
        JavaSourceSegmentApplicationService service = service(repositoryRoot);

        assertThatThrownBy(() -> service.read(query(range(0, 0, 0, 1), 0)))
                .isInstanceOf(SourceSegmentNotFoundException.class);
    }

    private JavaSourceSegmentApplicationService service(Path repositoryRoot) {
        RepositoryApplicationService repositories = mock(RepositoryApplicationService.class);
        RepositorySnapshot snapshot = new RepositorySnapshot(REPOSITORY_ID, repositoryRoot, REVISION);
        when(repositories.withSnapshot(eq(REPOSITORY_ID), eq(Optional.of(REVISION)), any()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    Function<RepositorySnapshot, JavaSourceSegmentResult> operation = invocation.getArgument(2);
                    return operation.apply(snapshot);
                });
        return new JavaSourceSegmentApplicationService(repositories, new RepositorySourceContainment());
    }

    private JavaSourceSegmentQuery query(SyntaxRange range, int contextLines) {
        return new JavaSourceSegmentQuery(
                REPOSITORY_ID,
                REVISION,
                new SourceRange(SOURCE_FILE, range),
                contextLines);
    }

    private void write(Path repositoryRoot, String content) throws IOException {
        Path source = repositoryRoot.resolve(SOURCE_FILE);
        Files.createDirectories(source.getParent());
        Files.writeString(source, content);
    }

    private SyntaxRange range(int startLine, int startCharacter, int endLine, int endCharacter) {
        return new SyntaxRange(
                new SyntaxPosition(startLine, startCharacter),
                new SyntaxPosition(endLine, endCharacter));
    }
}
