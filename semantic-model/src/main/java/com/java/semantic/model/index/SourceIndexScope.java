package com.java.semantic.model.index;

import com.java.semantic.model.codefact.CodeFactScope;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Flattened source-wide authority scope used to authorize a SOURCES row before it is read. */
public record SourceIndexScope(boolean usableScopes, List<String> packages, List<String> classKeys, List<String> methodKeys) {
    private static final String CLASS_SEPARATOR = "|";
    private static final String METHOD_SEPARATOR = "|";

    public SourceIndexScope {
        packages = immutable(packages, "packages", true);
        classKeys = immutable(classKeys, "class keys", false);
        methodKeys = immutable(methodKeys, "method keys", false);
        if (!usableScopes && (!packages.isEmpty() || !classKeys.isEmpty() || !methodKeys.isEmpty())) {
            throw new IllegalArgumentException("unusable source scope must be empty");
        }
        if (usableScopes && packages.isEmpty()) {
            throw new IllegalArgumentException("usable source scope requires a package");
        }
    }

    public static SourceIndexScope from(List<SymbolDocument> symbols) {
        List<SymbolDocument> requiredSymbols = List.copyOf(Objects.requireNonNull(symbols, "symbols are required"));
        if (requiredSymbols.isEmpty()) {
            return new SourceIndexScope(false, List.of(), List.of(), List.of());
        }
        Set<String> packages = new LinkedHashSet<>();
        Set<String> classKeys = new LinkedHashSet<>();
        Set<String> methodKeys = new LinkedHashSet<>();
        for (SymbolDocument symbol : requiredSymbols) {
            CodeFactScope scope = CodeFactScope.from(symbol.fact().identity());
            packages.add(scope.packageName());
            classKeys.add(classKey(scope.packageName(), scope.className()));
            scope.methodName().ifPresent(method -> methodKeys.add(methodKey(scope.packageName(), scope.className(), method,
                    scope.parameterTypes())));
        }
        return new SourceIndexScope(true, sorted(packages), sorted(classKeys), sorted(methodKeys));
    }

    public static String classKey(String packageName, String className) {
        return Objects.requireNonNull(packageName, "package name is required") + CLASS_SEPARATOR
                + Objects.requireNonNull(className, "class name is required");
    }

    public static String methodKey(String packageName, String className, String methodName, List<String> parameterTypes) {
        return classKey(packageName, className) + METHOD_SEPARATOR + Objects.requireNonNull(methodName, "method name is required")
                + METHOD_SEPARATOR + String.join(",", List.copyOf(Objects.requireNonNull(parameterTypes, "parameter types are required")));
    }

    private static List<String> immutable(List<String> values, String name, boolean allowEmptyValue) {
        List<String> requiredValues = List.copyOf(Objects.requireNonNull(values, name + " are required"));
        if (requiredValues.stream().anyMatch(value -> value.isBlank() && (!allowEmptyValue || !value.isEmpty()))) {
            throw new IllegalArgumentException(name + " must not contain blank values");
        }
        return sorted(new LinkedHashSet<>(requiredValues));
    }

    private static List<String> sorted(Set<String> values) {
        return values.stream().sorted(Comparator.naturalOrder()).toList();
    }
}
