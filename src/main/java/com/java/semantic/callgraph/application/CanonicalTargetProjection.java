package com.java.semantic.callgraph.application;

import com.java.semantic.identity.MethodTarget;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.semantic.domain.SemanticMethod;
import com.java.semantic.semantic.domain.SemanticPosition;
import com.java.semantic.semantic.domain.SemanticRange;
import com.java.semantic.syntax.domain.SyntaxPosition;
import com.java.semantic.syntax.domain.SyntaxRange;

import java.util.Optional;

/** 將語意方法投影為語法已證實的 canonical method target */
public final class CanonicalTargetProjection {

    public Optional<MethodTarget> project(
            RepositorySnapshot snapshot,
            RepositorySyntaxIndex index,
            SemanticMethod method) {
        Optional<String> sourceFile = snapshot.relativeSourceFile(method.location().uri());
        return sourceFile.flatMap(file -> index.target(
                        file,
                        method.packageName(),
                        method.className(),
                        method.methodName(),
                        method.parameterTypes())
                .or(() -> index.method(file, syntaxRange(method.location().range()))
                        .flatMap(signature -> signature.analysisTarget().target())));
    }

    private static SyntaxRange syntaxRange(SemanticRange range) {
        return new SyntaxRange(
                new SyntaxPosition(range.start().line(), range.start().character()),
                new SyntaxPosition(range.end().line(), range.end().character()));
    }
}
