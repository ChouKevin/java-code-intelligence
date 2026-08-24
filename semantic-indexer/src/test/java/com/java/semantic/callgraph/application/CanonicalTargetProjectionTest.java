package com.java.semantic.callgraph.application;

import com.java.semantic.syntax.domain.SourceTypeKind;

import com.java.semantic.model.codefact.JavaTypeIdentity;
import com.java.semantic.model.codefact.MethodTarget;
import com.java.semantic.model.codefact.SourceTypeIdentity;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.semantic.domain.SemanticLocation;
import com.java.semantic.semantic.domain.SemanticMethod;
import com.java.semantic.semantic.domain.SemanticPosition;
import com.java.semantic.semantic.domain.SemanticRange;
import com.java.semantic.semantic.domain.SemanticSourceClassification;
import com.java.semantic.syntax.domain.SourceTypeMetadata;
import com.java.semantic.syntax.domain.SourceMethodMetadata;
import com.java.semantic.syntax.domain.MethodTargetResolution;
import com.java.semantic.syntax.domain.RepositorySyntax;
import com.java.semantic.model.codefact.SourceRange;
import com.java.semantic.model.codefact.SyntaxPosition;
import com.java.semantic.model.codefact.SyntaxRange;
import com.java.semantic.syntax.domain.TypeReference;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CanonicalTargetProjectionTest {

    private static final RepositorySnapshot SNAPSHOT = new RepositorySnapshot(
            RepositoryId.of("orders"), Path.of("/fixture"), RepositoryRevision.ofSha("0".repeat(40)));

    private final CanonicalTargetProjection projection = new CanonicalTargetProjection();

    @Test
    void should_project_the_exact_repository_metadata_identity() {
        String sourceFile = "src/main/java/com/example/Port.java";
        MethodTarget exactTarget = target(sourceFile, "Port", "handle", List.of("Request"));
        MethodTarget rangeTarget = target(sourceFile, "FallbackPort", "fallback", List.of());
        SemanticMethod method = semanticMethod(
                "file:///fixture/src/main/java/com/example/Port.java", "Port", "handle", List.of("Request"));
        SyntaxRange exactTargetRange = new SyntaxRange(new SyntaxPosition(4, 1), new SyntaxPosition(4, 4));

        Optional<MethodTarget> projected = projection.project(
                new SemanticSourceClassification.LocalSource(sourceFile),
                index(type(exactTarget, exactTargetRange), type(rangeTarget)),
                method);

        assertThat(projected).contains(exactTarget);
    }

    @Test
    void should_fall_back_to_the_range_resolved_analysis_target_after_an_exact_identity_miss() {
        MethodTarget target = target(
                "src/main/java/com/example/Port.java", "Port", "handle", List.of("com.example.Request"));
        SemanticMethod method = semanticMethod(
                "file:///fixture/src/main/java/com/example/Port.java", "Port", "handle", List.of("Request"));

        Optional<MethodTarget> projected = projection.project(
                new SemanticSourceClassification.LocalSource(target.sourceFile()), index(type(target)), method);

        assertThat(projected).contains(target);
    }

    @Test
    void should_return_empty_when_exact_identity_and_declaration_range_cannot_be_resolved() {
        SemanticMethod method = semanticMethod(
                "file:///fixture/src/main/java/com/example/Missing.java", "Missing", "handle", List.of("Request"));
        MethodTarget indexedTarget = target("src/main/java/com/example/Port.java", "Port", "handle", List.of("Request"));

        Optional<MethodTarget> projected = projection.project(
                SemanticSourceClassification.UnprovableUri.INSTANCE, index(type(indexedTarget)), method);

        assertThat(projected).isEmpty();
    }

    private static RepositorySyntaxIndex index(SourceTypeMetadata... types) {
        return new RepositorySyntaxIndex(SNAPSHOT.repositoryId().value(), new RepositorySyntax(List.of(), List.of(types)));
    }

    private static MethodTarget target(String sourceFile, String className, String methodName, List<String> parameterTypes) {
        return new MethodTarget(
                new SourceTypeIdentity(
                        new JavaTypeIdentity("com.example", className),
                        sourceFile),
                methodName,
                parameterTypes);
    }

    private static SemanticMethod semanticMethod(
            String uri, String className, String methodName, List<String> parameterTypes) {
        SemanticRange range = new SemanticRange(new SemanticPosition(1, 0), new SemanticPosition(4, 0));
        return new SemanticMethod(
                "com.example", className, methodName, parameterTypes, "void", new SemanticLocation(uri, range, range));
    }

    private static SourceTypeMetadata type(MethodTarget target) {
        return type(target, new SyntaxRange(new SyntaxPosition(1, 0), new SyntaxPosition(4, 0)));
    }

    private static SourceTypeMetadata type(MethodTarget target, SyntaxRange methodRange) {
        SourceMethodMetadata method = new SourceMethodMetadata(
                target.methodName(), target.parameterTypes(), null, Optional.empty(),
                new SourceRange(target.sourceFile(), methodRange),
                List.<TypeReference>of(), Optional.empty(), List.of(), List.of(), List.of(), methodRange.start(),
                MethodTargetResolution.resolved(target), true, false, true, List.of());
        SyntaxRange typeRange = new SyntaxRange(new SyntaxPosition(0, 0), new SyntaxPosition(5, 0));
        return com.java.semantic.syntax.domain.SourceTypeMetadataFixture.sourceType(
                target.className(), target.packageName(), target.packageName() + "." + target.className(),
                target.sourceFile(), SourceTypeKind.CLASS, false,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(method), false, false, List.of(), typeRange,
                new SourceRange(target.sourceFile(), typeRange), false, List.of());
    }
}
