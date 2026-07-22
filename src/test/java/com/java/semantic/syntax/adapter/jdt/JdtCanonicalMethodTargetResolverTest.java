package com.java.semantic.syntax.adapter.jdt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.java.semantic.identity.MethodTarget;
import com.java.semantic.syntax.domain.AnalysisTargetStatus;
import com.java.semantic.syntax.domain.MethodTargetResolution;

import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class JdtCanonicalMethodTargetResolverTest {

    @Test
    void should_preserve_method_type_variable_names_alongside_erased_parameter_types(
            @TempDir Path tempDir) throws IOException {
        Path repositoryRoot = tempDir.resolve("repo");
        Path sourceRoot = repositoryRoot.resolve("src/main/java");
        Path packageDirectory = sourceRoot.resolve("com/example");
        Files.createDirectories(packageDirectory);
        Files.writeString(packageDirectory.resolve("Outer.java"), """
                package com.example;

                import java.util.List;

                class Outer {
                    class Inner {
                        <T extends Number> void process(int primitive, String[][] names, List<String> values, T... items) {
                        }
                    }
                }
                """);

        MethodTargetResolution resolution = resolveOnlyMethod(repositoryRoot, sourceRoot);

        assertThat(resolution.status()).isEqualTo(AnalysisTargetStatus.RESOLVED);
        assertThat(resolution.target()).contains(new MethodTarget(
                "src/main/java/com/example/Outer.java",
                "com.example", "Outer.Inner", "process",
                List.of("int", "java.lang.String[][]", "java.util.List", "T[]")));
    }

    @Test
    void should_erase_type_variables_declared_by_enclosing_types(@TempDir Path tempDir) throws IOException {
        Path repositoryRoot = tempDir.resolve("repo");
        Path sourceRoot = repositoryRoot.resolve("src/main/java");
        Path packageDirectory = sourceRoot.resolve("com/example");
        Files.createDirectories(packageDirectory);
        Files.writeString(packageDirectory.resolve("BoundedBox.java"), """
                package com.example;

                class BoundedBox<T extends Number> {
                    void process(T value) {
                    }
                }
                """);
        Files.writeString(packageDirectory.resolve("UnboundedBox.java"), """
                package com.example;

                class UnboundedBox<T> {
                    void process(T value) {
                    }
                }
                """);
        Files.writeString(packageDirectory.resolve("IntersectionBox.java"), """
                package com.example;

                class IntersectionBox<T extends Number & Comparable<T>> {
                    void process(T value) {
                    }
                }
                """);
        Files.writeString(packageDirectory.resolve("BoundedInterface.java"), """
                package com.example;

                interface BoundedInterface<T extends CharSequence> {
                    void process(T value);
                }
                """);

        List<MethodTargetResolution> resolutions = List.of(
                resolveTarget(repositoryRoot, sourceRoot, "BoundedBox.java", "BoundedBox"),
                resolveTarget(repositoryRoot, sourceRoot, "UnboundedBox.java", "UnboundedBox"),
                resolveTarget(repositoryRoot, sourceRoot, "IntersectionBox.java", "IntersectionBox"),
                resolveTarget(repositoryRoot, sourceRoot, "BoundedInterface.java", "BoundedInterface"));

        assertThat(resolutions).allSatisfy(resolution ->
                assertThat(resolution.status()).isEqualTo(AnalysisTargetStatus.RESOLVED));
        assertThat(resolutions).extracting(resolution -> resolution.target().orElseThrow().parameterTypes())
                .containsExactly(
                        List.of("java.lang.Number"),
                        List.of("java.lang.Object"),
                        List.of("java.lang.Number"),
                        List.of("java.lang.CharSequence"));
    }

    @Test
    void should_convert_nested_parameter_binding_qualified_names_to_source_style(@TempDir Path tempDir)
            throws IOException {
        Path repositoryRoot = tempDir.resolve("repo");
        Path sourceRoot = repositoryRoot.resolve("src/main/java");
        Path packageDirectory = sourceRoot.resolve("com/example");
        Files.createDirectories(packageDirectory);
        Files.writeString(packageDirectory.resolve("Outer.java"), """
                package com.example;

                class Outer {
                    static class Inner {
                    }

                    void process(Inner value) {
                    }
                }
                """);

        MethodTargetResolution resolution = resolveTarget(
                repositoryRoot, sourceRoot, "Outer.java", "CallerProvidedName");

        assertThat(resolution.status()).isEqualTo(AnalysisTargetStatus.RESOLVED);
        assertThat(resolution.target()).get()
                .satisfies(target -> assertThat(target.parameterTypes())
                        .containsExactly("com.example.Outer.Inner"));
    }

    @Test
    void should_fail_closed_when_a_parameter_binding_is_recovered(@TempDir Path tempDir) throws IOException {
        Path repositoryRoot = tempDir.resolve("repo");
        Path sourceRoot = repositoryRoot.resolve("src/main/java");
        Path packageDirectory = sourceRoot.resolve("com/example");
        Files.createDirectories(packageDirectory);
        Files.writeString(packageDirectory.resolve("Broken.java"), """
                package com.example;

                class Broken {
                    void process(MissingDependency dependency) {
                    }
                }
                """);

        MethodTargetResolution resolution = resolveOnlyMethod(repositoryRoot, sourceRoot);

        assertThat(resolution.status()).isEqualTo(AnalysisTargetStatus.UNRESOLVED);
        assertThat(resolution.reasonCode()).isEqualTo("METHOD_PARAMETER_BINDING_UNRESOLVED");
        assertThat(resolution.target()).isEmpty();
    }

    @Test
    void should_use_the_erased_qualified_binding_instead_of_an_ambiguous_simple_name(@TempDir Path tempDir)
            throws IOException {
        Path repositoryRoot = tempDir.resolve("repo");
        Path sourceRoot = repositoryRoot.resolve("src/main/java");
        Path packageDirectory = sourceRoot.resolve("com/example");
        Files.createDirectories(packageDirectory);
        Files.createDirectories(sourceRoot.resolve("a"));
        Files.createDirectories(sourceRoot.resolve("b"));
        Files.writeString(sourceRoot.resolve("a/Item.java"), "package a; public class Item {}");
        Files.writeString(sourceRoot.resolve("b/Item.java"), "package b; public class Item {}");
        Files.writeString(packageDirectory.resolve("UsesItem.java"), """
                package com.example;

                import b.Item;

                class UsesItem {
                    void process(Item item) {
                    }
                }
                """);

        MethodTargetResolution resolution = resolveTarget(repositoryRoot, sourceRoot, "UsesItem.java", "UsesItem");

        assertThat(resolution.target()).get()
                .satisfies(target -> assertThat(target.parameterTypes()).containsExactly("b.Item"));
    }

    @Test
    void should_distinguish_same_fqn_declarations_by_their_repository_relative_source(@TempDir Path tempDir)
            throws IOException {
        Path repositoryRoot = tempDir.resolve("repo");
        Path moduleASourceRoot = repositoryRoot.resolve("module-a/src/main/java");
        Path moduleBSourceRoot = repositoryRoot.resolve("module-b/src/main/java");
        writeOrder(moduleASourceRoot, "a");
        writeOrder(moduleBSourceRoot, "b");

        MethodTargetResolution moduleA = resolveTarget(repositoryRoot, moduleASourceRoot, "Order.java", "Order");
        MethodTargetResolution moduleB = resolveTarget(repositoryRoot, moduleBSourceRoot, "Order.java", "Order");

        assertThat(moduleA.target()).get().extracting(MethodTarget::sourceFile)
                .isEqualTo("module-a/src/main/java/com/example/Order.java");
        assertThat(moduleB.target()).get().extracting(MethodTarget::sourceFile)
                .isEqualTo("module-b/src/main/java/com/example/Order.java");
    }

    private MethodTargetResolution resolveOnlyMethod(Path repositoryRoot, Path sourceRoot) {
        return resolveTarget(repositoryRoot, sourceRoot, null, "Outer.Inner");
    }

    private MethodTargetResolution resolveTarget(
            Path repositoryRoot, Path sourceRoot, String sourceFileName, String nestedClassName) {
        List<SourceFile> files = new SourceFileScanner().scan(repositoryRoot, List.of(sourceRoot));
        List<ParsedSource> parsed = new ArrayList<>();
        new JdtAstParser(List.of(sourceRoot)).parse(files, parsed::add);
        ParsedSource source = Objects.isNull(sourceFileName) ? parsed.getFirst() : parsed.stream()
                .filter(candidate -> candidate.source().path().getFileName().toString().equals(sourceFileName))
                .findFirst()
                .orElseThrow();
        MethodDeclaration method = SourceTypes.allTypesOf(source.unit()).stream()
                .flatMap(type -> SourceTypes.declaredMethodsOf(type).stream())
                .findFirst()
                .orElseThrow();
        return new JdtCanonicalMethodTargetResolver().resolve(
                source.source(), "com.example", nestedClassName, method);
    }

    private void writeOrder(Path sourceRoot, String origin) throws IOException {
        Path packageDirectory = sourceRoot.resolve("com/example");
        Files.createDirectories(packageDirectory);
        Files.writeString(packageDirectory.resolve("Order.java"), """
                package com.example;

                class Order {
                    String origin() {
                        return \"%s\";
                    }
                }
                """.formatted(origin));
    }
}
