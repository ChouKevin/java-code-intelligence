package com.java.semantic.model.support;

import java.util.Objects;
import java.util.regex.Pattern;

public final class ModelValidation {

    private static final Pattern SHA_256 = Pattern.compile("^[0-9a-f]{64}$");

    private ModelValidation() {
        throw new UnsupportedOperationException("utility class");
    }

    public static String requiredText(String value, String name) {
        String text = Objects.requireNonNull(value, name + " is required");
        if (text.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return text;
    }

    public static String sha256(String value, String name) {
        String hash = requiredText(value, name);
        if (!SHA_256.matcher(hash).matches()) {
            throw new IllegalArgumentException(name + " must be a lowercase SHA-256 hash");
        }
        return hash;
    }

    public static String repositoryRelativePath(String value) {
        String path = requiredText(value, "repository-relative source path");
        require(path.length() <= 1024, "repository-relative source path must not exceed 1024 characters");
        if (path.startsWith("/") || path.contains("\\") || path.contains(":") || path.contains("//")) {
            throw new IllegalArgumentException("repository-relative source path is not normalized");
        }
        require(path.codePoints().noneMatch(character -> Character.isWhitespace(character)
                        || Character.isSpaceChar(character) || Character.isISOControl(character)),
                "repository-relative source path must not contain whitespace or control characters");
        for (String segment : path.split("/", -1)) {
            if (segment.isBlank() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("repository-relative source path contains an invalid segment");
            }
        }
        return path;
    }

    public static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
