package com.java.semantic.syntax.adapter.jdt;

import com.java.semantic.model.codefact.JavaTypeIdentity;
import com.java.semantic.model.codefact.MethodTarget;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.syntax.application.SourceMethodContextCandidate;
import com.java.semantic.syntax.application.SourceSymbolCandidate;
import com.java.semantic.syntax.application.SourceSymbolContext;
import com.java.semantic.syntax.application.SourceSymbolKind;
import com.java.semantic.syntax.application.SourceSymbolResolution;
import com.java.semantic.syntax.application.SourceSymbolResolutionQuery;
import com.java.semantic.syntax.application.SourceSymbolResolutionStatus;
import com.java.semantic.syntax.application.SourceTypeContextCandidate;
import com.java.semantic.model.codefact.SyntaxPosition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** JDT resolver 的分類、狀態與 sourcepath partition 邊界 */
class JdtSourceSymbolResolverTest {

    @Test
    void should_classify_source_declarations_and_method_occurrences(@TempDir Path repositoryRoot) throws IOException {
        write(repositoryRoot, "src/main/java/com/acme/OrderService.java", """
                package com.acme;
                class OrderService {
                    static final String TOPIC = "order." + "created";
                    static final Object CONSTANT_OBJECT = "object";
                    static final String RUNTIME = String.valueOf(1);
                    FraudPolicy fraudPolicy;
                    void confirm(Order order) {
                        FraudPolicy local = fraudPolicy;
                        local.review(order);
                    }
                    void work(Order order) {}
                    static class Nested {}
                }
                class FraudPolicy { void review(Order order) {} }
                class Order {}
                """);
        JdtSourceSymbolResolver resolver = new JdtSourceSymbolResolver();

        SourceSymbolResolution constant = resolver.resolve(snapshot(repositoryRoot), query(
                typeContext("OrderService"), "TOPIC", Optional.empty()));
        SourceSymbolResolution runtime = resolver.resolve(snapshot(repositoryRoot), query(
                typeContext("OrderService"), "RUNTIME", Optional.empty()));
        SourceSymbolResolution constantObject = resolver.resolve(snapshot(repositoryRoot), query(
                typeContext("OrderService"), "CONSTANT_OBJECT", Optional.empty()));
        SourceSymbolResolution method = resolver.resolve(snapshot(repositoryRoot), query(
                typeContext("OrderService"), "work", Optional.empty()));
        SourceSymbolResolution nested = resolver.resolve(snapshot(repositoryRoot), query(
                typeContext("OrderService"), "Nested", Optional.empty()));
        SourceSymbolResolution local = resolver.resolve(snapshot(repositoryRoot), query(
                methodContext("OrderService", "confirm", Optional.of(List.of("com.acme.Order"))),
                "local", Optional.empty()));

        assertThat(constant.status()).isEqualTo(SourceSymbolResolutionStatus.RESOLVED);
        assertThat(constant.candidates()).singleElement().isInstanceOfSatisfying(
                SourceSymbolCandidate.StaticConstant.class,
                candidate -> assertThat(candidate.initializerSource()).isEqualTo("\"order.\" + \"created\""));
        assertThat(runtime.candidates()).singleElement().satisfies(candidate ->
                assertThat(candidate.kind()).isEqualTo(SourceSymbolKind.FIELD));
        assertThat(constantObject.candidates()).singleElement().isInstanceOfSatisfying(
                SourceSymbolCandidate.StaticConstant.class,
                candidate -> assertThat(candidate.initializerSource()).isEqualTo("\"object\""));
        assertThat(method.candidates()).singleElement().isInstanceOf(SourceSymbolCandidate.Method.class);
        assertThat(nested.candidates()).singleElement().isInstanceOf(SourceSymbolCandidate.SourceType.class);
        assertThat(local.candidates()).singleElement().isInstanceOfSatisfying(
                SourceSymbolCandidate.VariableLike.class,
                candidate -> {
                    assertThat(candidate.kind()).isEqualTo(SourceSymbolKind.LOCAL_VARIABLE);
                    assertThat(candidate.representativeOccurrence().range().start().line()).isEqualTo(8);
                    assertThat(candidate.occurrenceCount()).isEqualTo(2);
                });
    }

    @Test
    void should_distinguish_overload_contexts_and_occurrence_groups(@TempDir Path repositoryRoot) throws IOException {
        Files.writeString(repositoryRoot.resolve("pom.xml"), """
                <project><packaging>pom</packaging><modules>
                  <module>module-context</module><module>module-z</module><module>module-a</module>
                </modules></project>
                """);
        write(repositoryRoot, "module-context/src/main/java/com/acme/Ambiguous.java", """
                package com.acme;
                class Ambiguous {
                    String foo;
                    AOwner aOwner;
                    ZOwner zOwner;
                    void pick(String value) {}
                    void pick() {}
                    void inspect(String foo) {
                        use(foo);
                        use(this.foo);
                        aOwner.run();
                        zOwner.run();
                    }
                    void use(String value) {}
                }
                """);
        write(repositoryRoot, "module-a/src/main/java/com/acme/ZOwner.java", """
                package com.acme;
                class ZOwner { void run() {} }
                """);
        write(repositoryRoot, "module-z/src/main/java/com/acme/AOwner.java", """
                package com.acme;
                class AOwner { void run() {} }
                """);
        JdtSourceSymbolResolver resolver = new JdtSourceSymbolResolver();

        SourceSymbolResolution overloadContext = resolver.resolve(snapshot(repositoryRoot), query(
                methodContext("Ambiguous", "pick", Optional.empty()), "foo", Optional.empty()));
        SourceSymbolResolution directOverloads = resolver.resolve(snapshot(repositoryRoot), query(
                typeContext("Ambiguous"), "pick", Optional.empty()));
        SourceSymbolResolution occurrences = resolver.resolve(snapshot(repositoryRoot), query(
                methodContext("Ambiguous", "inspect", Optional.of(List.of("java.lang.String"))),
                "foo", Optional.empty()));
        SourceSymbolResolution ownerOrderedMethods = resolver.resolve(snapshot(repositoryRoot), query(
                methodContext("Ambiguous", "inspect", Optional.of(List.of("java.lang.String"))),
                "run", Optional.empty()));

        assertThat(overloadContext.status()).isEqualTo(SourceSymbolResolutionStatus.AMBIGUOUS_CONTEXT);
        assertThat(overloadContext.contextCandidates()).hasSize(2).allSatisfy(candidate ->
                assertThat(candidate).isInstanceOf(SourceMethodContextCandidate.class));
        assertThat(overloadContext.contextCandidates())
                .map(SourceMethodContextCandidate.class::cast)
                .extracting(SourceMethodContextCandidate::target)
                .extracting(MethodTarget::parameterTypes)
                .containsExactly(List.of(), List.of("java.lang.String"));
        assertThat(directOverloads.status()).isEqualTo(SourceSymbolResolutionStatus.AMBIGUOUS_SYMBOL);
        assertThat(directOverloads.candidates())
                .map(SourceSymbolCandidate.Method.class::cast)
                .extracting(candidate -> candidate.identity().parameterTypes())
                .containsExactly(List.of(), List.of("java.lang.String"));
        assertThat(occurrences.status()).isEqualTo(SourceSymbolResolutionStatus.AMBIGUOUS_OCCURRENCE);
        assertThat(occurrences.candidates()).hasSize(2);
        assertThat(ownerOrderedMethods.status()).isEqualTo(SourceSymbolResolutionStatus.AMBIGUOUS_OCCURRENCE);
        assertThat(ownerOrderedMethods.candidates())
                .map(SourceSymbolCandidate.Method.class::cast)
                .extracting(candidate -> candidate.identity().className())
                .containsExactly("AOwner", "ZOwner");
    }

    @Test
    void should_reuse_colliding_root_partition_for_selected_file(@TempDir Path repositoryRoot) throws IOException {
        Files.writeString(repositoryRoot.resolve("pom.xml"), """
                <project><packaging>pom</packaging><modules>
                  <module>module-b</module><module>module-a</module>
                </modules></project>
                """);
        write(repositoryRoot, "module-a/src/main/java/com/acme/Owner.java", """
                package com.acme;
                class Owner { static final String TOKEN = Shared.VALUE; }
                class Shared { static final String VALUE = "a"; }
                """);
        write(repositoryRoot, "module-b/src/main/java/com/acme/Owner.java", """
                package com.acme;
                class Owner { static final String TOKEN = Shared.VALUE; }
                class Shared { static final String VALUE = runtime(); static String runtime() { return "b"; } }
                """);
        JdtSourceSymbolResolver resolver = new JdtSourceSymbolResolver();

        SourceSymbolResolution ambiguous = resolver.resolve(snapshot(repositoryRoot), query(
                typeContext("Owner"), "TOKEN", Optional.empty()));
        SourceSymbolResolution selected = resolver.resolve(snapshot(repositoryRoot), query(
                new SourceSymbolContext(
                        new JavaTypeIdentity("com.acme", "Owner"),
                        Optional.of("module-a/src/main/java/com/acme/Owner.java"),
                        Optional.empty()),
                "TOKEN", Optional.empty()));

        assertThat(ambiguous.status()).isEqualTo(SourceSymbolResolutionStatus.AMBIGUOUS_CONTEXT);
        assertThat(ambiguous.contextCandidates()).hasSize(2).allSatisfy(candidate ->
                assertThat(candidate).isInstanceOf(SourceTypeContextCandidate.class));
        assertThat(ambiguous.contextCandidates())
                .map(SourceTypeContextCandidate.class::cast)
                .extracting(SourceTypeContextCandidate::sourceFile)
                .containsExactly(
                        "module-a/src/main/java/com/acme/Owner.java",
                        "module-b/src/main/java/com/acme/Owner.java");
        assertThat(selected.candidates()).singleElement().isInstanceOf(SourceSymbolCandidate.StaticConstant.class);
    }

    @Test
    void should_report_unresolved_type_parameter_and_exact_position_mismatch(@TempDir Path repositoryRoot)
            throws IOException {
        write(repositoryRoot, "src/main/java/com/acme/GenericService.java", """
                package com.acme;
                class GenericService<T> {
                    void inspect(T value) { consume(value); }
                    void consume(T value) {}
                }
                """);
        JdtSourceSymbolResolver resolver = new JdtSourceSymbolResolver();
        SourceSymbolContext context = methodContext(
                "GenericService", "inspect", Optional.empty());

        SourceSymbolResolution unresolved = resolver.resolve(snapshot(repositoryRoot), query(
                context, "T", Optional.empty()));
        SourceSymbolResolution mismatch = resolver.resolve(snapshot(repositoryRoot), query(
                context, "value", Optional.of(new SyntaxPosition(0, 0))));

        assertThat(unresolved.status()).isEqualTo(SourceSymbolResolutionStatus.UNRESOLVED_BINDING);
        assertThat(unresolved.issueSummaries()).singleElement().satisfies(issue ->
                assertThat(issue.code().name()).isEqualTo("UNSUPPORTED_SOURCE_CONSTRUCT"));
        assertThat(mismatch.status()).isEqualTo(SourceSymbolResolutionStatus.POSITION_MISMATCH);
        assertThat(mismatch.candidates()).isEmpty();
    }

    private SourceSymbolResolutionQuery query(
            SourceSymbolContext context,
            String symbol,
            Optional<SyntaxPosition> position) {
        return new SourceSymbolResolutionQuery(
                RepositoryId.of("order-service"), RepositoryRevision.ofSha("0".repeat(40)), context, symbol, position);
    }

    private SourceSymbolContext typeContext(String className) {
        return new SourceSymbolContext(
                new JavaTypeIdentity("com.acme", className), Optional.empty(), Optional.empty());
    }

    private SourceSymbolContext methodContext(
            String className,
            String methodName,
            Optional<List<String>> parameterTypes) {
        return new SourceSymbolContext(
                new JavaTypeIdentity("com.acme", className),
                Optional.empty(),
                Optional.of(new SourceSymbolContext.MethodContext(methodName, parameterTypes)));
    }

    private RepositorySnapshot snapshot(Path repositoryRoot) {
        return new RepositorySnapshot(
                RepositoryId.of("order-service"), repositoryRoot, RepositoryRevision.ofSha("0".repeat(40)));
    }

    private void write(Path repositoryRoot, String relativePath, String source) throws IOException {
        Path file = repositoryRoot.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
    }
}
