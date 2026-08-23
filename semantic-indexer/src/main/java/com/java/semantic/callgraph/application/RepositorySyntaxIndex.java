package com.java.semantic.callgraph.application;

import com.java.semantic.callgraph.domain.MethodId;
import com.java.semantic.identity.JavaIdentityNormalizer;
import com.java.semantic.identity.MethodTarget;
import com.java.semantic.syntax.domain.RepositorySyntax;
import com.java.semantic.syntax.domain.SourceMethodMetadata;
import com.java.semantic.syntax.domain.SourceTypeMetadata;
import com.java.semantic.syntax.domain.SyntaxRange;

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

    private static final Comparator<SourceTypeMetadata> SOURCE_TYPE_ORDER = Comparator
            .comparing((SourceTypeMetadata metadata) -> metadata.declaration().identity().fullyQualifiedName())
            .thenComparing(metadata -> metadata.declaration().identity().sourceFile())
            .thenComparingInt(metadata -> metadata.declaration().declarationLocation().range().start().line())
            .thenComparingInt(metadata -> metadata.declaration().declarationLocation().range().start().character())
            .thenComparingInt(metadata -> metadata.declaration().declarationLocation().range().end().line())
            .thenComparingInt(metadata -> metadata.declaration().declarationLocation().range().end().character());

    private static final Comparator<IndexedMethod> METHOD_ORDER = Comparator
            .comparing((IndexedMethod indexed) -> indexed.sourceType().declaration().identity().fullyQualifiedName())
            .thenComparing(indexed -> indexed.sourceType().declaration().identity().sourceFile())
            .thenComparing(indexed -> indexed.method().declarationLocation().sourceFile())
            .thenComparingInt(indexed -> indexed.method().declarationLocation().range().start().line())
            .thenComparingInt(indexed -> indexed.method().declarationLocation().range().start().character())
            .thenComparingInt(indexed -> indexed.method().declarationLocation().range().end().line())
            .thenComparingInt(indexed -> indexed.method().declarationLocation().range().end().character())
            .thenComparing(indexed -> indexed.method().name())
            .thenComparing(indexed -> parameterProjection(indexed.method().paramTypes()))
            .thenComparingInt(indexed -> indexed.sourceType().declaration().declarationLocation().range().start().line())
            .thenComparingInt(indexed -> indexed.sourceType().declaration().declarationLocation().range().start().character())
            .thenComparingInt(indexed -> indexed.sourceType().declaration().declarationLocation().range().end().line())
            .thenComparingInt(indexed -> indexed.sourceType().declaration().declarationLocation().range().end().character());

    private final String repoId;
    private final Map<String, List<SourceTypeMetadata>> sourceTypesByFqn;
    private final Map<MethodId, List<SourceMethodMetadata>> methodsById;
    private final Map<MethodTarget, SourceMethodMetadata> methodsByTarget;
    private final Map<MethodTarget, SourceTypeMetadata> sourceTypesByTarget;
    private final Map<SourceRange, SourceMethodMetadata> methodsBySourceRange;

    public RepositorySyntaxIndex(String repoId, RepositorySyntax syntax) {
        this.repoId = Objects.requireNonNull(repoId, "repoId is required");
        Objects.requireNonNull(syntax, "syntax is required");
        this.sourceTypesByFqn = indexSourceTypes(syntax.sourceTypes());
        this.methodsById = indexMethods(syntax.sourceTypes());
        this.methodsByTarget = indexMethodsByTarget(syntax.sourceTypes());
        this.sourceTypesByTarget = indexSourceTypesByTarget(syntax.sourceTypes());
        this.methodsBySourceRange = indexMethodsBySourceRange(syntax.sourceTypes());
    }

    public List<SourceTypeMetadata> sourceTypes(String fullyQualifiedName) {
        return sourceTypesByFqn.getOrDefault(fullyQualifiedName, List.of());
    }

    public List<SourceMethodMetadata> methods(MethodId methodId) {
        return methodsById.getOrDefault(methodId, List.of());
    }

    /** Finds only a method with an exact resolved source-qualified identity. */
    public Optional<SourceMethodMetadata> method(MethodTarget target) {
        return Optional.ofNullable(methodsByTarget.get(target));
    }

    /** Finds the declaring type by the method's exact repository-qualified target. */
    public Optional<SourceTypeMetadata> sourceType(MethodTarget target) {
        return Optional.ofNullable(sourceTypesByTarget.get(target));
    }

    /** Finds only a resolved method at its exact repository-relative declaration range. */
    public Optional<SourceMethodMetadata> method(String sourceFile, SyntaxRange declarationRange) {
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

    private Map<String, List<SourceTypeMetadata>> indexSourceTypes(List<SourceTypeMetadata> sourceTypes) {
        Map<String, List<SourceTypeMetadata>> mutable = new HashMap<>();
        for (SourceTypeMetadata sourceType : sourceTypes) {
            mutable.computeIfAbsent(sourceType.declaration().identity().fullyQualifiedName(), ignored -> new ArrayList<>())
                    .add(sourceType);
        }
        Map<String, List<SourceTypeMetadata>> indexed = new HashMap<>();
        mutable.forEach((key, values) -> indexed.put(key, values.stream().sorted(SOURCE_TYPE_ORDER).toList()));
        return Collections.unmodifiableMap(new LinkedHashMap<>(indexed));
    }

    private Map<MethodId, List<SourceMethodMetadata>> indexMethods(List<SourceTypeMetadata> sourceTypes) {
        Map<MethodId, List<IndexedMethod>> mutable = new HashMap<>();
        for (SourceTypeMetadata sourceType : sourceTypes) {
            for (SourceMethodMetadata method : sourceType.members().methods()) {
                MethodId methodId = new MethodId(
                        repoId,
                        sourceType.declaration().identity().javaType().packageName(),
                        JavaIdentityNormalizer.className(
                                sourceType.declaration().identity().javaType().packageName(),
                                sourceType.declaration().identity().javaType().className()),
                        method.name(),
                        method.paramTypes());
                mutable.computeIfAbsent(methodId, ignored -> new ArrayList<>())
                        .add(new IndexedMethod(sourceType, method));
            }
        }
        Map<MethodId, List<SourceMethodMetadata>> indexed = new HashMap<>();
        mutable.forEach((key, values) -> indexed.put(
                key, values.stream().sorted(METHOD_ORDER).map(IndexedMethod::method).toList()));
        return Collections.unmodifiableMap(new LinkedHashMap<>(indexed));
    }

    private Map<MethodTarget, SourceMethodMetadata> indexMethodsByTarget(List<SourceTypeMetadata> sourceTypes) {
        Map<MethodTarget, SourceMethodMetadata> indexed = new LinkedHashMap<>();
        for (IndexedMethod indexedMethod : sortedMethods(sourceTypes)) {
            indexedMethod.method().analysisTarget().target()
                    .ifPresent(target -> indexed.putIfAbsent(target, indexedMethod.method()));
        }
        return Collections.unmodifiableMap(indexed);
    }

    private Map<MethodTarget, SourceTypeMetadata> indexSourceTypesByTarget(List<SourceTypeMetadata> sourceTypes) {
        Map<MethodTarget, SourceTypeMetadata> indexed = new LinkedHashMap<>();
        for (IndexedMethod indexedMethod : sortedMethods(sourceTypes)) {
            indexedMethod.method().analysisTarget().target()
                    .ifPresent(target -> indexed.putIfAbsent(target, indexedMethod.sourceType()));
        }
        return Collections.unmodifiableMap(indexed);
    }

    private Map<SourceRange, SourceMethodMetadata> indexMethodsBySourceRange(List<SourceTypeMetadata> sourceTypes) {
        Map<SourceRange, SourceMethodMetadata> indexed = new LinkedHashMap<>();
        for (IndexedMethod indexedMethod : sortedMethods(sourceTypes)) {
            indexedMethod.method().analysisTarget().target().ifPresent(target -> indexed.putIfAbsent(
                    new SourceRange(
                            indexedMethod.method().declarationLocation().sourceFile(),
                            indexedMethod.method().declarationLocation().range()),
                    indexedMethod.method()));
        }
        return Collections.unmodifiableMap(indexed);
    }

    private List<IndexedMethod> sortedMethods(List<SourceTypeMetadata> sourceTypes) {
        List<IndexedMethod> methods = new ArrayList<>();
        for (SourceTypeMetadata sourceType : sourceTypes) {
            for (SourceMethodMetadata method : sourceType.members().methods()) {
                methods.add(new IndexedMethod(sourceType, method));
            }
        }
        return methods.stream().sorted(METHOD_ORDER).toList();
    }

    private static String parameterProjection(List<String> parameterTypes) {
        return String.join("\u0000", parameterTypes);
    }

    private record IndexedMethod(SourceTypeMetadata sourceType, SourceMethodMetadata method) {
    }

    private record SourceRange(String sourceFile, SyntaxRange declarationRange) {
    }
}
