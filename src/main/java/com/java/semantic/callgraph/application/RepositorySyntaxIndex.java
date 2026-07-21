package com.java.semantic.callgraph.application;

import com.java.semantic.callgraph.domain.MethodId;
import com.java.semantic.syntax.domain.ClassMetadata;
import com.java.semantic.syntax.domain.ClassMetadata.MethodSignature;
import com.java.semantic.syntax.domain.RepositorySyntax;
import com.java.semantic.identity.PolicyIdentity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable deterministic lookup over one repository syntax snapshot. */
public final class RepositorySyntaxIndex {

    private static final Comparator<ClassMetadata> CLASS_ORDER = Comparator
            .comparing(ClassMetadata::fullyQualifiedName)
            .thenComparing(ClassMetadata::filePath)
            .thenComparingInt(metadata -> metadata.range().start().line())
            .thenComparingInt(metadata -> metadata.range().start().character())
            .thenComparing(metadata -> metadata.source().text());

    private static final Comparator<IndexedMethod> METHOD_ORDER = Comparator
            .comparing((IndexedMethod indexed) -> indexed.metadata().fullyQualifiedName())
            .thenComparing(indexed -> indexed.metadata().filePath())
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

    public RepositorySyntaxIndex(String repoId, RepositorySyntax syntax) {
        this.repoId = Objects.requireNonNull(repoId, "repoId is required");
        Objects.requireNonNull(syntax, "syntax is required");
        this.classesByFqn = indexClasses(syntax.classes());
        this.methodsById = indexMethods(syntax.classes());
    }

    public List<ClassMetadata> classes(String fullyQualifiedName) {
        return classesByFqn.getOrDefault(fullyQualifiedName, List.of());
    }

    public List<MethodSignature> methods(MethodId methodId) {
        return methodsById.getOrDefault(methodId, List.of());
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

    private record IndexedMethod(ClassMetadata metadata, MethodSignature method) {
    }
}
