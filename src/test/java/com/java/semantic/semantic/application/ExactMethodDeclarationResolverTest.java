package com.java.semantic.semantic.application;

import com.java.semantic.identity.MethodTarget;
import com.java.semantic.semantic.domain.SemanticBindingAmbiguousException;
import com.java.semantic.semantic.domain.SemanticDeclarationAnchor;
import com.java.semantic.semantic.domain.SemanticTargetNotFoundException;
import com.java.semantic.syntax.adapter.jdt.JdtSyntaxExtractionService;
import com.java.semantic.syntax.domain.AnalysisTargetStatus;
import com.java.semantic.syntax.domain.ClassMetadata;
import com.java.semantic.syntax.domain.MethodTargetResolution;
import com.java.semantic.syntax.domain.RepositorySyntax;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExactMethodDeclarationResolverTest {

    @TempDir
    Path repositoryRoot;

    @Test
    void should_anchor_only_the_requested_complete_target_when_same_simple_signature_exists_in_another_file()
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
        MethodTarget requested = syntax.classes().stream()
                .filter(metadata -> "First".equals(metadata.className()))
                .flatMap(metadata -> metadata.methods().stream())
                .map(method -> method.analysisTarget().target().orElseThrow())
                .findFirst()
                .orElseThrow();

        SemanticDeclarationAnchor anchor = new ExactMethodDeclarationResolver().resolve(syntax, requested);

        assertThat(anchor.target()).isEqualTo(requested);
        assertThat(anchor.namePosition().line()).isEqualTo(2);
        assertThat(anchor.namePosition().character()).isEqualTo(16);
    }

    @Test
    void should_reject_a_wrong_normalized_source_before_any_jdt_request() throws IOException {
        writeSource("alpha/First.java", """
                package com.example.alpha;
                public class First {
                    public void same(String value) { }
                }
                """);
        RepositorySyntax syntax = new JdtSyntaxExtractionService().extract(repositoryRoot);
        MethodTarget actual = syntax.classes().stream()
                .filter(metadata -> "First".equals(metadata.className()))
                .flatMap(metadata -> metadata.methods().stream())
                .map(method -> method.analysisTarget().target().orElseThrow())
                .findFirst()
                .orElseThrow();
        MethodTarget requested = new MethodTarget(
                "src/main/java/alpha/Elsewhere.java",
                actual.packageName(),
                actual.className(),
                actual.methodName(),
                actual.parameterTypes());

        assertThatThrownBy(() -> new ExactMethodDeclarationResolver().resolve(syntax, requested))
                .isInstanceOf(SemanticTargetNotFoundException.class);
    }

    @Test
    void should_filter_by_source_package_nested_class_and_method_before_anchoring() throws IOException {
        writeSource("selected/Outer.java", """
                package com.example.selected;
                class Outer {
                    class Inner {
                        void selected(String value) { }
                        void ignored(String value) { }
                    }
                }
                """);
        writeSource("other/Outer.java", """
                package com.example.other;
                class Outer {
                    class Inner {
                        void selected(String value) { }
                    }
                }
                """);
        RepositorySyntax syntax = new JdtSyntaxExtractionService().extract(repositoryRoot);
        MethodTarget requested = syntax.classes().stream()
                .filter(metadata -> "com.example.selected".equals(metadata.packageName()))
                .filter(metadata -> "Outer.Inner".equals(metadata.className()))
                .flatMap(metadata -> metadata.methods().stream())
                .filter(method -> "selected".equals(method.name()))
                .map(method -> method.analysisTarget().target().orElseThrow())
                .findFirst()
                .orElseThrow();

        SemanticDeclarationAnchor anchor = new ExactMethodDeclarationResolver().resolve(syntax, requested);

        assertThat(anchor.target()).isEqualTo(requested);
        assertThat(anchor.namePosition()).isEqualTo(new com.java.semantic.semantic.domain.SemanticPosition(3, 13));
    }

    @Test
    void should_retain_complete_sorted_candidates_from_duplicate_fqn_module_proof() throws IOException {
        Files.writeString(repositoryRoot.resolve("pom.xml"), """
                <project>
                    <packaging>pom</packaging>
                    <modules><module>module-a</module><module>module-b</module></modules>
                </project>
                """);
        writeModuleSource("module-a", "a");
        writeModuleSource("module-b", "b");
        RepositorySyntax extracted = new JdtSyntaxExtractionService().extract(repositoryRoot);
        ClassMetadata moduleA = extracted.classes().stream()
                .filter(metadata -> metadata.methods().stream().anyMatch(method -> method.analysisTarget()
                        .target().map(target -> target.sourceFile().startsWith("module-a/")).orElse(false)))
                .findFirst()
                .orElseThrow();
        MethodTarget requested = moduleA.methods().getFirst().analysisTarget().target().orElseThrow();
        MethodTarget alternate = extracted.classes().stream()
                .filter(metadata -> metadata.methods().stream().anyMatch(method -> method.analysisTarget()
                        .target().map(target -> target.sourceFile().startsWith("module-b/")).orElse(false)))
                .findFirst()
                .orElseThrow()
                .methods()
                .getFirst()
                .analysisTarget()
                .target()
                .orElseThrow();
        MethodTargetResolution ambiguous = new MethodTargetResolution(
                AnalysisTargetStatus.AMBIGUOUS,
                Optional.empty(),
                List.of(alternate, requested),
                "DUPLICATE_FQN_PROOF");
        RepositorySyntax syntax = new RepositorySyntax(
                List.of(), List.of(withResolution(moduleA, ambiguous)));

        assertThatThrownBy(() -> new ExactMethodDeclarationResolver().resolve(syntax, requested))
                .isInstanceOfSatisfying(SemanticBindingAmbiguousException.class, exception ->
                        assertThat(exception.candidates()).containsExactly(requested, alternate));
    }

    @Test
    void should_reject_duplicate_exact_declarations_with_their_actual_canonical_proofs() throws IOException {
        writeSource("alpha/First.java", """
                package com.example.alpha;
                public class First {
                    public void same(String value) { }
                }
                """);
        RepositorySyntax extracted = new JdtSyntaxExtractionService().extract(repositoryRoot);
        ClassMetadata metadata = extracted.classes().getFirst();
        MethodTarget requested = metadata.methods().getFirst().analysisTarget().target().orElseThrow();
        RepositorySyntax syntax = new RepositorySyntax(List.of(), List.of(metadata, metadata));

        assertThatThrownBy(() -> new ExactMethodDeclarationResolver().resolve(syntax, requested))
                .isInstanceOfSatisfying(SemanticBindingAmbiguousException.class, exception ->
                        assertThat(exception.candidates()).containsExactly(requested, requested));
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

    private ClassMetadata withResolution(ClassMetadata metadata, MethodTargetResolution resolution) {
        ClassMetadata.MethodSignature method = metadata.methods().getFirst();
        ClassMetadata.MethodSignature replacement = new ClassMetadata.MethodSignature(
                method.name(), method.paramTypes(), method.annotations(), method.sql(), method.sqlSource(),
                method.startLine(), method.endLine(), method.range(), method.source(),
                method.parameterTypeReferences(), method.returnType(), method.invocations(), method.annotationEvidence(),
                method.bodyTypeReferences(), method.namePosition(), resolution,
                method.executableDeclaration(), method.overridableDeclaration());
        return new ClassMetadata(
                metadata.className(), metadata.packageName(), metadata.fullyQualifiedName(), metadata.sourceFile(),
                metadata.kind(), metadata.isAbstract(), metadata.implementedTypes(), metadata.extendedTypes(),
                metadata.annotations(), metadata.imports(), metadata.fields(), List.of(replacement),
                metadata.hasFluentAccessors(), metadata.hasChainedAccessors(), metadata.profiles(), metadata.range(),
                metadata.source(), metadata.primary(), metadata.beanQualifiers(), metadata.annotationEvidence());
    }
}
