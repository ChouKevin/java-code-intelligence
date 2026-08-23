package com.java.semantic.syntax.domain;

import com.java.semantic.syntax.domain.SourceMethodMetadata;

import com.java.semantic.model.codefact.JavaTypeIdentity;
import com.java.semantic.model.codefact.MethodTarget;
import com.java.semantic.model.codefact.SourceTypeIdentity;
import com.java.semantic.syntax.adapter.jdt.JdtSyntaxExtractionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 驗證 canonical 方法宣告解析器只依賴語法層的完整目標證明 */
class CanonicalMethodDeclarationResolverTest {

    private static final String TARGET_NOT_FOUND = "TARGET_NOT_FOUND";

    @TempDir
    Path repositoryRoot;

    @Test
    void should_resolve_only_the_requested_complete_target_when_same_simple_signature_exists_in_another_file()
            throws IOException {
        writeSource("alpha/First.java", """
                package com.example.alpha;
                public class First {
                    public void same(String value) { }
                }
                """);
        writeSource("beta/Second.java", """
                package com.example.beta;
                public class Second {
                    public void same(String value) { }
                }
                """);
        RepositorySyntax syntax = new JdtSyntaxExtractionService().extract(repositoryRoot);
        MethodTarget requested = targetOf(syntax, "First", "same");

        MethodTargetResolution resolution = new CanonicalMethodDeclarationResolver().resolve(syntax, requested);

        assertThat(resolution.status()).isEqualTo(AnalysisTargetStatus.RESOLVED);
        assertThat(resolution.target()).contains(requested);
        assertThat(resolution.candidates()).isEmpty();
        assertThat(resolution.reasonCode()).isEmpty();
    }

    @Test
    void should_return_unresolved_when_the_normalized_source_does_not_match() throws IOException {
        writeSource("alpha/First.java", """
                package com.example.alpha;
                public class First {
                    public void same(String value) { }
                }
                """);
        RepositorySyntax syntax = new JdtSyntaxExtractionService().extract(repositoryRoot);
        MethodTarget actual = targetOf(syntax, "First", "same");
        MethodTarget requested = new MethodTarget(
                new SourceTypeIdentity(
                        new JavaTypeIdentity(actual.packageName(), actual.className()),
                        "src/main/java/alpha/Elsewhere.java"),
                actual.methodName(),
                actual.parameterTypes());

        MethodTargetResolution resolution = new CanonicalMethodDeclarationResolver().resolve(syntax, requested);

        assertThat(resolution.status()).isEqualTo(AnalysisTargetStatus.UNRESOLVED);
        assertThat(resolution.target()).isEmpty();
        assertThat(resolution.candidates()).isEmpty();
        assertThat(resolution.reasonCode()).isEqualTo(TARGET_NOT_FOUND);
    }

    @Test
    void should_resolve_by_source_package_nested_class_method_and_array_parameters() throws IOException {
        writeSource("selected/Outer.java", """
                package com.example.selected;
                class Outer {
                    class Inner {
                        void selected(String[] value) { }
                        void ignored(String[] value) { }
                    }
                }
                """);
        writeSource("other/Outer.java", """
                package com.example.other;
                class Outer {
                    class Inner {
                        void selected(String[] value) { }
                    }
                }
                """);
        RepositorySyntax syntax = new JdtSyntaxExtractionService().extract(repositoryRoot);
        MethodTarget requested = syntax.sourceTypes().stream()
                .filter(metadata -> "com.example.selected".equals(metadata.declaration().identity().javaType().packageName()))
                .filter(metadata -> "Outer.Inner".equals(metadata.declaration().identity().javaType().className()))
                .flatMap(metadata -> metadata.members().methods().stream())
                .filter(method -> "selected".equals(method.name()))
                .map(method -> method.analysisTarget().target().orElseThrow())
                .findFirst()
                .orElseThrow();

        MethodTargetResolution resolution = new CanonicalMethodDeclarationResolver().resolve(syntax, requested);

        assertThat(requested.parameterTypes()).containsExactly("java.lang.String[]");
        assertThat(resolution.status()).isEqualTo(AnalysisTargetStatus.RESOLVED);
        assertThat(resolution.target()).contains(requested);
    }

    @Test
    void should_retain_complete_ordered_unique_candidates_from_duplicate_fqn_module_proof() throws IOException {
        Files.writeString(repositoryRoot.resolve("pom.xml"), """
                <project>
                    <packaging>pom</packaging>
                    <modules><module>module-a</module><module>module-b</module></modules>
                </project>
                """);
        writeModuleSource("module-a", "a");
        writeModuleSource("module-b", "b");
        RepositorySyntax extracted = new JdtSyntaxExtractionService().extract(repositoryRoot);
        SourceTypeMetadata moduleA = extracted.sourceTypes().stream()
                .filter(metadata -> metadata.members().methods().stream().anyMatch(method -> method.analysisTarget()
                        .target().map(target -> target.sourceFile().startsWith("module-a/")).orElse(false)))
                .findFirst()
                .orElseThrow();
        MethodTarget requested = moduleA.members().methods().getFirst().analysisTarget().target().orElseThrow();
        MethodTarget alternate = extracted.sourceTypes().stream()
                .filter(metadata -> metadata.members().methods().stream().anyMatch(method -> method.analysisTarget()
                        .target().map(target -> target.sourceFile().startsWith("module-b/")).orElse(false)))
                .findFirst()
                .orElseThrow()
                .members().methods()
                .getFirst()
                .analysisTarget()
                .target()
                .orElseThrow();
        MethodTargetResolution ambiguous = new MethodTargetResolution(
                AnalysisTargetStatus.AMBIGUOUS,
                Optional.empty(),
                List.of(alternate, requested),
                "DUPLICATE_FQN_PROOF");
        RepositorySyntax syntax = new RepositorySyntax(List.of(), List.of(withResolution(moduleA, ambiguous)));

        MethodTargetResolution resolution = new CanonicalMethodDeclarationResolver().resolve(syntax, requested);

        assertThat(resolution.status()).isEqualTo(AnalysisTargetStatus.AMBIGUOUS);
        assertThat(resolution.target()).isEmpty();
        assertThat(resolution.candidates()).containsExactly(requested, alternate);
        assertThat(resolution.reasonCode()).isEqualTo("DUPLICATE_FQN_PROOF");
    }

    @Test
    void should_reject_duplicate_metadata_with_the_same_exact_proof() throws IOException {
        writeSource("alpha/First.java", """
                package com.example.alpha;
                public class First {
                    public void same(String value) { }
                }
                """);
        RepositorySyntax extracted = new JdtSyntaxExtractionService().extract(repositoryRoot);
        SourceTypeMetadata metadata = extracted.sourceTypes().getFirst();
        MethodTarget requested = metadata.members().methods().getFirst().analysisTarget().target().orElseThrow();
        RepositorySyntax syntax = new RepositorySyntax(List.of(), List.of(metadata, metadata));

        assertThatThrownBy(() -> new CanonicalMethodDeclarationResolver().resolve(syntax, requested))
                .isInstanceOfSatisfying(DuplicateMethodDeclarationProofException.class, exception -> {
                    assertThat(exception.target()).isEqualTo(requested);
                    assertThat(exception.proofCount()).isEqualTo(2);
                });
    }

    private MethodTarget targetOf(RepositorySyntax syntax, String className, String methodName) {
        return syntax.sourceTypes().stream()
                .filter(metadata -> className.equals(metadata.declaration().identity().javaType().className()))
                .flatMap(metadata -> metadata.members().methods().stream())
                .filter(method -> methodName.equals(method.name()))
                .map(method -> method.analysisTarget().target().orElseThrow())
                .findFirst()
                .orElseThrow();
    }

    private void writeSource(String relativePath, String source) throws IOException {
        Path sourceFile = repositoryRoot.resolve("src/main/java").resolve(relativePath);
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, source);
    }

    private void writeModuleSource(String module, String origin) throws IOException {
        Path sourceFile = repositoryRoot.resolve(module).resolve("src/main/java/com/example/Order.java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, """
                package com.example;
                class Order {
                    String origin() { return \"%s\"; }
                }
                """.formatted(origin));
    }

    private SourceTypeMetadata withResolution(SourceTypeMetadata metadata, MethodTargetResolution resolution) {
        SourceMethodMetadata method = metadata.members().methods().getFirst();
        SourceMethodMetadata replacement = new SourceMethodMetadata(
                method.name(), method.paramTypes(), method.sqlSource(), method.annotationSqlLocation(),
                method.declarationLocation(),
                method.parameterTypeReferences(), method.returnType(), method.invocations(), method.annotationEvidence(),
                method.bodyTypeReferences(), method.namePosition(), resolution,
                method.executableDeclaration(), method.abstractDeclaration(), method.overridableDeclaration());
        return new SourceTypeMetadata(metadata.declaration(), metadata.relationships(),
                new SourceTypeMembers(metadata.members().fields(), List.of(replacement),
                        metadata.members().fluentSetters(), metadata.members().chainedAccessors()),
                metadata.frameworkFacts(), metadata.compilationUnit());
    }
}
