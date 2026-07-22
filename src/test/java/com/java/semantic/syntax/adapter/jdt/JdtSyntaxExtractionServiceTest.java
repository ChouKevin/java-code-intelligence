package com.java.semantic.syntax.adapter.jdt;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import com.java.semantic.syntax.domain.ClassMetadata;
import com.java.semantic.syntax.domain.RepositorySyntax;
import com.java.semantic.syntax.domain.SyntaxInvocation;
import com.java.semantic.syntax.domain.SyntaxPosition;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/** 逐檔隔離：單一檔案抽取失敗不得讓整個 repository 掃出零筆 */
class JdtSyntaxExtractionServiceTest {

    private static final String HOSTILE_CLASS = "Hostile";
    private static final String RESTRICTED_JAVA_PATH = "RESTRICTED_JAVA_PATH_SENTINEL";
    private static final String RESTRICTED_XML_PATH = "RESTRICTED_XML_PATH_SENTINEL";
    private static final String RESTRICTED_THROWABLE = "RESTRICTED_THROWABLE_SENTINEL";

    @Test
    void should_still_return_the_other_files_when_one_source_file_fails_to_extract(@TempDir Path tempDir)
            throws IOException {
        Path repositoryRoot = tempDir.resolve("order-service");
        Path sourceRoot = repositoryRoot.resolve("src/main/java/com/example");
        Files.createDirectories(sourceRoot);
        Files.writeString(sourceRoot.resolve("Hostile.java"),
                "package com.example; public class Hostile { public String boom() { return \"\"; } }");
        Files.writeString(sourceRoot.resolve("Healthy.java"),
                "package com.example; public class Healthy { public String ok() { return \"\"; } }");
        Files.writeString(sourceRoot.resolve("AlsoHealthy.java"),
                "package com.example; public class AlsoHealthy { public String ok() { return \"\"; } }");

        RepositorySyntax syntax = new JdtSyntaxExtractionService(new ExplodingSourceSyntaxExtractor())
                .extract(repositoryRoot);

        assertThat(syntax.classes())
                .as("一個檔案拋例外時，其餘檔案的結果必須留下，否則整個 repo 靜默變成零筆")
                .extracting(ClassMetadata::fullyQualifiedName)
                .containsExactly("com.example.AlsoHealthy", "com.example.Healthy");
    }

    @Test
    void should_return_every_file_when_no_source_file_fails_to_extract(@TempDir Path tempDir) throws IOException {
        Path repositoryRoot = tempDir.resolve("order-service");
        Path sourceRoot = repositoryRoot.resolve("src/main/java/com/example");
        Files.createDirectories(sourceRoot);
        Files.writeString(sourceRoot.resolve("Healthy.java"),
                "package com.example; public class Healthy { public String ok() { return \"\"; } }");

        RepositorySyntax syntax = new JdtSyntaxExtractionService(new ExplodingSourceSyntaxExtractor())
                .extract(repositoryRoot);

        assertThat(syntax.classes())
                .extracting(ClassMetadata::fullyQualifiedName)
                .containsExactly("com.example.Healthy");
    }

    @Test
    void should_keep_same_fqn_methods_from_separate_modules_distinct_by_repository_source(@TempDir Path tempDir)
            throws IOException {
        Path repositoryRoot = tempDir.resolve("order-service");
        Files.createDirectories(repositoryRoot);
        Files.writeString(repositoryRoot.resolve("pom.xml"), """
                <project>
                  <packaging>pom</packaging>
                  <modules><module>module-b</module><module>module-a</module></modules>
                </project>
                """);
        writeOrderClass(repositoryRoot.resolve("module-a/src/main/java/com/example/Order.java"), "a");
        writeOrderClass(repositoryRoot.resolve("module-b/src/main/java/com/example/Order.java"), "b");

        RepositorySyntax syntax = new JdtSyntaxExtractionService().extract(repositoryRoot);

        assertThat(syntax.classes()).extracting(ClassMetadata::fullyQualifiedName)
                .containsExactly("com.example.Order", "com.example.Order");
        assertThat(syntax.classes()).flatExtracting(ClassMetadata::methods)
                .extracting(method -> method.analysisTarget().status())
                .containsOnly(com.java.semantic.syntax.domain.AnalysisTargetStatus.RESOLVED);
        assertThat(syntax.classes()).flatExtracting(ClassMetadata::methods)
                .extracting(method -> method.analysisTarget().target().orElseThrow().sourceFile())
                .containsExactly("module-a/src/main/java/com/example/Order.java",
                        "module-b/src/main/java/com/example/Order.java");
    }

    @Test
    void should_fail_closed_for_consumers_of_same_fqn_declared_from_different_source_layouts(
            @TempDir Path tempDir) throws IOException {
        RepositorySyntax forward = extractCollidingFqnRepository(tempDir.resolve("forward"),
                List.of("module-a", "module-b", "module-c"));
        RepositorySyntax reverse = extractCollidingFqnRepository(tempDir.resolve("reverse"),
                List.of("module-c", "module-b", "module-a"));

        assertCollidingFqnDeclarationsAreIsolated(forward);
        assertCollidingFqnDeclarationsAreIsolated(reverse);
    }

    private RepositorySyntax extractCollidingFqnRepository(Path repositoryRoot, List<String> modules)
            throws IOException {
        Files.createDirectories(repositoryRoot);
        Files.writeString(repositoryRoot.resolve("pom.xml"), """
                <project>
                  <packaging>pom</packaging>
                  <modules>%s</modules>
                </project>
                """.formatted(modules.stream().map(module -> "<module>" + module + "</module>")
                .collect(Collectors.joining())));
        writeOrderClass(repositoryRoot.resolve("module-a/src/main/java/com/example/Order.java"), "a");
        writeOrderClass(repositoryRoot.resolve("module-b/src/main/java/layout/Order.java"), "b");
        Path consumer = repositoryRoot.resolve("module-c/src/main/java/com/example/Consumer.java");
        Files.createDirectories(consumer.getParent());
        Files.writeString(consumer, """
                package com.example;

                class Consumer {
                    void use(Order order) {
                    }
                }
                """);
        return new JdtSyntaxExtractionService().extract(repositoryRoot);
    }

    private void assertCollidingFqnDeclarationsAreIsolated(RepositorySyntax syntax) {
        assertThat(syntax.classes()).extracting(ClassMetadata::fullyQualifiedName)
                .containsExactly("com.example.Consumer", "com.example.Order", "com.example.Order");
        List<ClassMetadata> orders = syntax.classes().stream()
                .filter(metadata -> "com.example.Order".equals(metadata.fullyQualifiedName()))
                .toList();
        assertThat(orders)
                .flatExtracting(ClassMetadata::methods)
                .extracting(method -> method.analysisTarget().status())
                .containsOnly(com.java.semantic.syntax.domain.AnalysisTargetStatus.RESOLVED);
        assertThat(orders)
                .flatExtracting(ClassMetadata::methods)
                .extracting(method -> method.analysisTarget().target().orElseThrow().sourceFile())
                .containsExactlyInAnyOrder(
                        "module-a/src/main/java/com/example/Order.java",
                        "module-b/src/main/java/layout/Order.java");
        assertThat(syntax.classes().stream()
                .filter(metadata -> "com.example.Consumer".equals(metadata.fullyQualifiedName()))
                .flatMap(metadata -> metadata.methods().stream())
                .filter(method -> "use".equals(method.name()))
                .findFirst()
                .orElseThrow()
                .analysisTarget().status())
                .isEqualTo(com.java.semantic.syntax.domain.AnalysisTargetStatus.UNRESOLVED);
    }

    private void writeOrderClass(Path path, String marker) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, """
                package com.example;

                class Order {
                    String origin() {
                        return \"%s\";
                    }
                }
                """.formatted(marker));
    }

    @Test
    void should_not_expose_restricted_java_or_xml_failure_channels_in_logs(@TempDir Path tempDir)
            throws IOException {
        Path repositoryRoot = tempDir.resolve("order-service");
        Path sourceRoot = repositoryRoot.resolve("src/main/java/com/example");
        Path resourceRoot = repositoryRoot.resolve("src/main/resources/mappers");
        Files.createDirectories(sourceRoot);
        Files.createDirectories(resourceRoot);
        Files.writeString(sourceRoot.resolve(HOSTILE_CLASS + RESTRICTED_JAVA_PATH + ".java"),
                "package com.example; class HostileRestricted {} ");
        Files.writeString(resourceRoot.resolve(RESTRICTED_XML_PATH + ".xml"),
                "<mapper namespace=\"restricted\"><select id=\"broken\">" + RESTRICTED_THROWABLE);

        Logger syntaxLogger = (Logger) LoggerFactory.getLogger(JdtSyntaxExtractionService.class);
        Logger xmlLogger = (Logger) LoggerFactory.getLogger(MapperXmlSqlExtractor.class);
        ListAppender<ILoggingEvent> syntaxAppender = appender(syntaxLogger);
        ListAppender<ILoggingEvent> xmlAppender = appender(xmlLogger);
        try {
            new JdtSyntaxExtractionService(new ExplodingSourceSyntaxExtractor()).extract(repositoryRoot);

            assertSafeFailureEvents(syntaxAppender.list, "JAVA_SYNTAX_EXTRACTION_FAILED");
            assertSafeFailureEvents(xmlAppender.list, "MAPPER_XML_PARSE_FAILED");
        } finally {
            detach(syntaxLogger, syntaxAppender);
            detach(xmlLogger, xmlAppender);
        }
    }

    @Test
    void should_log_unexpected_per_source_failures_at_error_without_raw_source_data(@TempDir Path tempDir)
            throws IOException {
        Path repositoryRoot = tempDir.resolve("order-service");
        Path sourceRoot = repositoryRoot.resolve("src/main/java/com/example");
        Files.createDirectories(sourceRoot);
        Files.writeString(sourceRoot.resolve(HOSTILE_CLASS + ".java"),
                "package com.example; class Hostile {} ");
        Logger logger = (Logger) LoggerFactory.getLogger(JdtSyntaxExtractionService.class);
        ListAppender<ILoggingEvent> appender = appender(logger);
        try {
            new JdtSyntaxExtractionService(new UnexpectedExplodingSourceSyntaxExtractor()).extract(repositoryRoot);

            List<ILoggingEvent> failures = appender.list.stream()
                    .filter(event -> event.getFormattedMessage().contains("JAVA_SYNTAX_EXTRACTION_FAILED"))
                    .toList();
            assertThat(failures).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                assertThat(event.getFormattedMessage())
                        .contains("exceptionType=IllegalStateException")
                        .doesNotContain("RESTRICTED_UNEXPECTED_SOURCE_SENTINEL", repositoryRoot.toString());
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally {
            detach(logger, appender);
        }
    }

    @Test
    void should_extract_explicit_anchors_for_every_invocation_kind(@TempDir Path tempDir) throws IOException {
        Path repositoryRoot = tempDir.resolve("anchor-service");
        Path sourceRoot = repositoryRoot.resolve("src/main/java/com/example");
        Files.createDirectories(sourceRoot);
        String source = """
                package com.example;

                import static com.example.AnchorTools.decorate;
                import java.util.function.Function;

                public class AnchorFixture {
                    public void run() {
                        helper();
                        new AnchorFixture();
                        Function<String, String> lambda = value -> helper();
                        Runnable reference = this::helper;
                        decorate("value");
                    }

                    private void helper() {
                    }

                    private static String decorate(String value) {
                        return value;
                    }
                }
                """;
        Files.writeString(sourceRoot.resolve("AnchorFixture.java"), source);
        Files.writeString(sourceRoot.resolve("AnchorTools.java"), """
                package com.example;

                public final class AnchorTools {
                    private AnchorTools() {
                    }

                    public static String decorate(String value) {
                        return value;
                    }
                }
                """);

        RepositorySyntax syntax = new JdtSyntaxExtractionService().extract(repositoryRoot);
        ClassMetadata.MethodSignature run = syntax.classes().stream()
                .filter(type -> "com.example.AnchorFixture".equals(type.fullyQualifiedName()))
                .flatMap(type -> type.methods().stream())
                .filter(method -> "run".equals(method.name()))
                .findFirst()
                .orElseThrow();
        List<SyntaxInvocation> invocations = run.invocations();
        List<String> lines = source.lines().toList();

        assertThat(invocations).extracting(SyntaxInvocation::kind)
                .contains(
                        SyntaxInvocation.InvocationKind.METHOD,
                        SyntaxInvocation.InvocationKind.CONSTRUCTOR,
                        SyntaxInvocation.InvocationKind.METHOD_REFERENCE)
                .doesNotContain(SyntaxInvocation.InvocationKind.LAMBDA);
        SyntaxInvocation method = invocation(invocations, "helper()");
        assertThat(method.resolutionAnchor()).isEqualTo(position(lines, 7, "helper"));
        SyntaxInvocation constructor = invocation(invocations, "new AnchorFixture()");
        assertThat(constructor.resolutionAnchor()).isEqualTo(position(lines, 8, "AnchorFixture"));
        SyntaxInvocation methodReference = invocation(invocations, "this::helper");
        assertThat(methodReference.resolutionAnchor()).isEqualTo(position(lines, 10, "helper"));
        SyntaxInvocation staticImport = invocation(invocations, "decorate(\"value\")");
        assertThat(staticImport.resolutionAnchor()).isEqualTo(position(lines, 11, "decorate"));
    }

    private SyntaxInvocation invocation(List<SyntaxInvocation> invocations, String expression) {
        return invocations.stream()
                .filter(invocation -> expression.equals(invocation.expression()))
                .findFirst()
                .orElseThrow();
    }

    private SyntaxPosition position(List<String> lines, int line, String token) {
        return new SyntaxPosition(line, lines.get(line).indexOf(token));
    }

    private ListAppender<ILoggingEvent> appender(Logger logger) {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private void detach(Logger logger, ListAppender<ILoggingEvent> appender) {
        logger.detachAppender(appender);
        appender.stop();
    }

    private void assertSafeFailureEvents(List<ILoggingEvent> events, String category) {
        List<ILoggingEvent> failureEvents = events.stream()
                .filter(event -> event.getFormattedMessage().contains(category))
                .toList();
        assertThat(failureEvents).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage())
                    .contains(category)
                    .doesNotContain(RESTRICTED_JAVA_PATH, RESTRICTED_XML_PATH, RESTRICTED_THROWABLE);
            assertThat(Arrays.toString(event.getArgumentArray()))
                    .doesNotContain(RESTRICTED_JAVA_PATH, RESTRICTED_XML_PATH, RESTRICTED_THROWABLE);
            assertThat(event.getThrowableProxy()).isNull();
        });
    }

    /**
     * 模擬 recovered node 打到 UnsupportedTypeFormException 或 bodyDeclarations 的未檢查轉型
     * <p>
     * setStatementsRecovery(true) 會產出這段程式碼沒見過的節點形狀，失敗類別是真實的
     */
    private static final class ExplodingSourceSyntaxExtractor extends SourceSyntaxExtractor {

        @Override
        SourceSyntax extractFrom(ParsedSource parsed, MapperXmlSqlExtractor.SqlIndex sqlIndex) {
            if (parsed.source().relativePath().contains(HOSTILE_CLASS)) {
                throw new UnsupportedTypeFormException(RESTRICTED_THROWABLE);
            }
            return super.extractFrom(parsed, sqlIndex);
        }
    }

    private static final class UnexpectedExplodingSourceSyntaxExtractor extends SourceSyntaxExtractor {

        @Override
        SourceSyntax extractFrom(ParsedSource parsed, MapperXmlSqlExtractor.SqlIndex sqlIndex) {
            if (parsed.source().relativePath().contains(HOSTILE_CLASS)) {
                throw new IllegalStateException("RESTRICTED_UNEXPECTED_SOURCE_SENTINEL");
            }
            return super.extractFrom(parsed, sqlIndex);
        }
    }
}
