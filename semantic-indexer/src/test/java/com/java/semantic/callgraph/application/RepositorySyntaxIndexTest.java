package com.java.semantic.callgraph.application;

import java.util.List;
import java.util.Optional;

import com.java.semantic.model.codefact.JavaTypeIdentity;
import com.java.semantic.model.codefact.MethodTarget;
import com.java.semantic.model.codefact.SourceRange;
import com.java.semantic.model.codefact.SourceTypeIdentity;
import com.java.semantic.model.codefact.SyntaxPosition;
import com.java.semantic.model.codefact.SyntaxRange;
import com.java.semantic.syntax.domain.MethodTargetResolution;
import com.java.semantic.syntax.domain.RepositorySyntax;
import com.java.semantic.syntax.domain.SourceMethodMetadata;
import com.java.semantic.syntax.domain.SourceTypeKind;
import com.java.semantic.syntax.domain.SourceTypeMetadata;
import com.java.semantic.syntax.domain.SourceTypeMetadataFixture;
import com.java.semantic.syntax.domain.TypeReference;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RepositorySyntaxIndexTest {

    @Test
    void should_match_only_exact_resolved_method_target_or_source_range() {
        String sourceFile = "src/main/java/com/example/FlagFixture.java";
        MethodTarget target = target(sourceFile, "FlagFixture", "open", List.of("java.lang.String"));
        SyntaxRange resolvedRange = range(15, 4, 18, 5);
        SyntaxRange unresolvedRange = range(20, 4, 22, 5);
        SourceMethodMetadata resolved = method(target, resolvedRange, MethodTargetResolution.resolved(target));
        SourceMethodMetadata unresolved = method(
                "unresolved", List.of("com.example.MissingDependency"), sourceFile, unresolvedRange,
                MethodTargetResolution.unresolved("missing-parameter-type"));
        SourceTypeMetadata metadata = SourceTypeMetadataFixture.sourceType(
                target.className(), target.packageName(), target.fullyQualifiedClassName(), sourceFile,
                SourceTypeKind.CLASS, false, List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(resolved, unresolved), false, false, List.of(), range(0, 0, 30, 0),
                new SourceRange(sourceFile, range(0, 0, 30, 0)), false, List.of());
        RepositorySyntaxIndex index = new RepositorySyntaxIndex(
                "orders", new RepositorySyntax(List.of(), List.of(metadata)));

        assertThat(index.method(target)).contains(resolved);
        assertThat(index.method(target.sourceFile(), resolvedRange)).contains(resolved);
        assertThat(index.method("src/main/java/com/example/Other.java", resolvedRange)).isEmpty();
        assertThat(index.method(target.sourceFile(), new SyntaxRange(
                new SyntaxPosition(resolvedRange.start().line(), resolvedRange.start().character() + 1),
                resolvedRange.end()))).isEmpty();
        assertThat(index.method(new MethodTarget(
                new SourceTypeIdentity(
                        new JavaTypeIdentity(target.packageName(), target.className()),
                        "src/main/java/com/example/Other.java"),
                target.methodName(),
                target.parameterTypes()))).isEmpty();
        assertThat(index.method(new MethodTarget(
                new SourceTypeIdentity(
                        new JavaTypeIdentity("other.example", target.className()),
                        target.sourceFile()),
                target.methodName(),
                target.parameterTypes()))).isEmpty();
        assertThat(index.method(new MethodTarget(
                new SourceTypeIdentity(
                        new JavaTypeIdentity(target.packageName(), "Other"),
                        target.sourceFile()),
                target.methodName(),
                target.parameterTypes()))).isEmpty();
        assertThat(index.method(new MethodTarget(
                new SourceTypeIdentity(
                        new JavaTypeIdentity(target.packageName(), target.className()),
                        target.sourceFile()),
                "other",
                target.parameterTypes()))).isEmpty();
        assertThat(index.method(new MethodTarget(
                new SourceTypeIdentity(
                        new JavaTypeIdentity(target.packageName(), target.className()),
                        target.sourceFile()),
                target.methodName(),
                List.of("int")))).isEmpty();
        assertThat(index.method(target.sourceFile(), unresolvedRange)).isEmpty();
    }

    private static SourceMethodMetadata method(MethodTarget target, SyntaxRange declarationRange,
            MethodTargetResolution resolution) {
        return method(target.methodName(), target.parameterTypes(), target.sourceFile(), declarationRange, resolution);
    }

    private static SourceMethodMetadata method(
            String name,
            List<String> parameterTypes,
            String sourceFile,
            SyntaxRange declarationRange,
            MethodTargetResolution resolution) {
        return new SourceMethodMetadata(
                name, parameterTypes, null, Optional.empty(), new SourceRange(sourceFile, declarationRange),
                List.<TypeReference>of(), Optional.empty(), List.of(), List.of(), List.of(), declarationRange.start(),
                resolution, true, false, true, List.of());
    }

    private static MethodTarget target(
            String sourceFile, String className, String methodName, List<String> parameterTypes) {
        return new MethodTarget(
                new SourceTypeIdentity(new JavaTypeIdentity("com.example", className), sourceFile),
                methodName, parameterTypes);
    }

    private static SyntaxRange range(int startLine, int startCharacter, int endLine, int endCharacter) {
        return new SyntaxRange(
                new SyntaxPosition(startLine, startCharacter), new SyntaxPosition(endLine, endCharacter));
    }
}
