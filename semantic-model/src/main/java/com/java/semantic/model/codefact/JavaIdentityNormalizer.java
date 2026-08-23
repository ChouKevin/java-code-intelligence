package com.java.semantic.model.codefact;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class JavaIdentityNormalizer {

    private JavaIdentityNormalizer() {
        throw new UnsupportedOperationException("utility class");
    }

    public static String className(String packageName, String declarationName) {
        Objects.requireNonNull(packageName, "package name is required");
        String value = Objects.requireNonNull(declarationName, "declaration name is required").trim().replace('$', '.');
        String prefix = packageName.isBlank() ? "" : packageName + ".";
        return value.startsWith(prefix) ? value.substring(prefix.length()) : value;
    }

    public static List<String> parameterTypes(List<String> parameterTypes) {
        List<String> normalized = new ArrayList<>();
        for (String parameterType : Objects.requireNonNull(parameterTypes, "parameter types are required")) {
            normalized.add(parameterType(parameterType));
        }
        return List.copyOf(normalized);
    }

    public static String parameterType(String parameterType) {
        String value = Objects.requireNonNull(parameterType, "parameter type is required").trim();
        if (value.isBlank()) {
            return "";
        }
        int genericStart = value.indexOf('<');
        if (genericStart >= 0) {
            int genericEnd = value.lastIndexOf('>');
            String suffix = genericEnd >= genericStart ? value.substring(genericEnd + 1) : "";
            value = value.substring(0, genericStart) + suffix;
        }
        value = value.replace("...", "[]").trim();
        StringBuilder arraySuffix = new StringBuilder();
        while (value.endsWith("[]")) {
            arraySuffix.append("[]");
            value = value.substring(0, value.length() - 2).trim();
        }
        int lastSegment = value.lastIndexOf('.');
        String simpleName = lastSegment >= 0 ? value.substring(lastSegment + 1) : value;
        return simpleName + arraySuffix;
    }
}
