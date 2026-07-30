package com.java.semantic.callgraph.application;

import com.java.semantic.callgraph.domain.MethodId;
import com.java.semantic.identity.MethodTarget;
import com.java.semantic.syntax.domain.ClassMetadata;
import com.java.semantic.syntax.domain.ClassMetadata.MethodSignature;
import com.java.semantic.syntax.domain.RepositorySyntax;
import com.java.semantic.syntax.domain.SyntaxRange;
import com.java.semantic.identity.PolicyIdentity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable deterministic lookup over one repository syntax snapshot. */
public final class RepositorySyntaxIndex {

    private static final Comparator<ClassMetadata> CLASS_ORDER = Comparator
            .comparing(ClassMetadata::fullyQualifiedName)
            .thenComparing(ClassMetadata::sourceFile)
            .thenComparingInt(metadata -> metadata.range().start().line())
            .thenComparingInt(metadata -> metadata.range().start().character())
            .thenComparing(metadata -> metadata.source().text());

    private static final Comparator<IndexedMethod> METHOD_ORDER = Comparator
            .comparing((IndexedMethod indexed) -> indexed.metadata().fullyQualifiedName())
            .thenComparing(indexed -> indexed.metadata().sourceFile())
            .thenComparingInt(indexed -> indexed.method().range().start().line())
            .thenComparingInt(indexed -> indexed.method().range().start().character())
            .thenComparingInt(indexed -> indexed.method().range().end().line())
            .thenComparingInt(indexed -> indexed.method().range().end().character())
            .thenComparing(indexed -> String.join("\u0000", indexed.method().annotations()))
            .thenComparing(indexed -> Objects.toString(indexed.method().sql(), ""))
            .thenComparing(indexed -> Objects.toString(indexed.method().sqlSource(), ""))
            .thenComparing(indexed -> indexed.method().source().text())
            .thenComparing(indexed -> indexed.method().parameterTypeReferences().toString())
            .thenComparing(indexed -> indexed.method().returnType().toString())
            .thenComparing(indexed -> indexed.method().invocations().toString())
            .thenComparingInt(indexed -> indexed.metadata().range().start().line())
            .thenComparingInt(indexed -> indexed.metadata().range().start().character())
            .thenComparingInt(indexed -> indexed.metadata().range().end().line())
            .thenComparingInt(indexed -> indexed.metadata().range().end().character())
            .thenComparing(indexed -> String.join("\u0000", indexed.metadata().annotations()))
            .thenComparing(indexed -> indexed.metadata().source().text());

    private final String repoId;
    private final Map<String, List<ClassMetadata>> classesByFqn;
    private final Map<MethodId, List<MethodSignature>> methodsById;
    private final Map<MethodTarget, MethodSignature> methodsByTarget;
    private final Map<MethodTarget, ClassMetadata> metadataByTarget;
    private final Map<SourceRange, MethodSignature> methodsBySourceRange;

    public RepositorySyntaxIndex(String repoId, RepositorySyntax syntax) {
        this.repoId = Objects.requireNonNull(repoId, "repoId is required");
        Objects.requireNonNull(syntax, "syntax is required");
        this.classesByFqn = indexClasses(syntax.classes());
        this.methodsById = indexMethods(syntax.classes());
        this.methodsByTarget = indexMethodsByTarget(syntax.classes());
        this.metadataByTarget = indexMetadataByTarget(syntax.classes());
        this.methodsBySourceRange = indexMethodsBySourceRange(syntax.classes());
    }

    public List<ClassMetadata> classes(String fullyQualifiedName) {
        return classesByFqn.getOrDefault(fullyQualifiedName, List.of());
    }

    public List<MethodSignature> methods(MethodId methodId) {
        return methodsById.getOrDefault(methodId, List.of());
    }

    /** Finds only a method with an exact resolved source-qualified identity. */
    public Optional<MethodSignature> method(MethodTarget target) {
        return Optional.ofNullable(methodsByTarget.get(target));
    }

    /** Finds the declaring type by the method's exact repository-qualified target. */
    public Optional<ClassMetadata> classMetadata(MethodTarget target) {
        return Optional.ofNullable(metadataByTarget.get(target));
    }

    /** Finds only a resolved method at its exact repository-relative declaration range. */
    public Optional<MethodSignature> method(String sourceFile, SyntaxRange declarationRange) {
        return Optional.ofNullable(methodsBySourceRange.get(new SourceRange(sourceFile, declarationRange)));
    }

    /** Finds a complete target only when its source-qualified declaration is proven by syntax. */
    public Optional<MethodTarget> target(
            String sourceFile,
            String packageName,
            String className,
            String methodName,
            List<String> parameterTypes) {
        return methodsByTarget.keySet().stream()
                .filter(target -> target.sourceFile().equals(sourceFile))
                .filter(target -> target.packageName().equals(packageName))
                .filter(target -> target.className().equals(className))
                .filter(target -> target.methodName().equals(methodName))
                .filter(target -> target.parameterTypes().equals(parameterTypes))
                .findFirst();
    }

    private Map<String, List<ClassMetadata>> indexClasses(List<ClassMetadata> classes) {
        Map<String, List<ClassMetadata>> mutable = new HashMap<>();
        for (ClassMetadata metadata : classes) {
            mutable.computeIfAbsent(metadata.fullyQualifiedName(), ignored -> new ArrayList<>()).add(metadata);
        }
        Map<String, List<ClassMetadata>> indexed = new HashMap<>();
        mutable.forEach((key, values) -> indexed.put(key, values.stream().sorted(CLASS_ORDER).toList()));
        return Collections.unmodifiableMap(new LinkedHashMap<>(indexed));
    }

    private Map<MethodId, List<MethodSignature>> indexMethods(List<ClassMetadata> classes) {
        Map<MethodId, List<IndexedMethod>> mutable = new HashMap<>();
        for (ClassMetadata metadata : classes) {
            for (MethodSignature method : metadata.methods()) {
                MethodId methodId = new MethodId(
                        repoId,
                        metadata.packageName(),
                        PolicyIdentity.className(metadata.packageName(), metadata.className()),
                        method.name(),
                        method.paramTypes());
                mutable.computeIfAbsent(methodId, ignored -> new ArrayList<>())
                        .add(new IndexedMethod(metadata, method));
            }
        }
        Map<MethodId, List<MethodSignature>> indexed = new HashMap<>();
        mutable.forEach((key, values) -> indexed.put(
                key, values.stream().sorted(METHOD_ORDER).map(IndexedMethod::method).toList()));
        return Collections.unmodifiableMap(new LinkedHashMap<>(indexed));
    }

    private Map<MethodTarget, MethodSignature> indexMethodsByTarget(List<ClassMetadata> classes) {
        Map<MethodTarget, MethodSignature> indexed = new LinkedHashMap<>();
        for (IndexedMethod indexedMethod : sortedMethods(classes)) {
            indexedMethod.method().analysisTarget().target()
                    .ifPresent(target -> indexed.putIfAbsent(target, indexedMethod.method()));
        }
        return Collections.unmodifiableMap(indexed);
    }

    private Map<MethodTarget, ClassMetadata> indexMetadataByTarget(List<ClassMetadata> classes) {
        Map<MethodTarget, ClassMetadata> indexed = new LinkedHashMap<>();
        for (IndexedMethod indexedMethod : sortedMethods(classes)) {
            indexedMethod.method().analysisTarget().target()
                    .ifPresent(target -> indexed.putIfAbsent(target, indexedMethod.metadata()));
        }
        return Collections.unmodifiableMap(indexed);
    }

    private Map<SourceRange, MethodSignature> indexMethodsBySourceRange(List<ClassMetadata> classes) {
        Map<SourceRange, MethodSignature> indexed = new LinkedHashMap<>();
        for (IndexedMethod indexedMethod : sortedMethods(classes)) {
            indexedMethod.method().analysisTarget().target().ifPresent(target -> indexed.putIfAbsent(
                    new SourceRange(target.sourceFile(), indexedMethod.method().range()), indexedMethod.method()));
        }
        return Collections.unmodifiableMap(indexed);
    }

    private List<IndexedMethod> sortedMethods(List<ClassMetadata> classes) {
        List<IndexedMethod> methods = new ArrayList<>();
        for (ClassMetadata metadata : classes) {
            for (MethodSignature method : metadata.methods()) {
                methods.add(new IndexedMethod(metadata, method));
            }
        }
        return methods.stream().sorted(METHOD_ORDER).toList();
    }

    private record IndexedMethod(ClassMetadata metadata, MethodSignature method) {
    }

    private record SourceRange(String sourceFile, SyntaxRange declarationRange) {
    }
}
