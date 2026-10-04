package com.java.semantic.model.source;

import java.util.Objects;
import java.util.Set;

public final class SourcePathPolicy {

    private static final Set<String> EXCLUDED_DIRECTORIES = Set.of(
            ".git", "target", "build", ".gradle", "node_modules", "generated");

    private SourcePathPolicy() { }

    public static String requireFile(String path) {
        Objects.requireNonNull(path, "source path");
        if (path.isEmpty()) {
            throw new IllegalArgumentException("source file path must not be empty");
        }
        return requireDirectory(path);
    }

    public static String requireDirectory(String path) {
        Objects.requireNonNull(path, "source directory");
        if (path.isEmpty()) {
            return path;
        }
        if (path.charAt(0) == '/' || isDrivePath(path)) {
            throw new IllegalArgumentException("source path must be relative");
        }
        int segmentStart = 0;
        for (int index = 0; index < path.length(); index++) {
            char character = path.charAt(index);
            if (character == '\\' || Character.isISOControl(character)) {
                throw new IllegalArgumentException("source path contains an unsupported character");
            }
            if (Character.isHighSurrogate(character)) {
                if (index + 1 >= path.length() || !Character.isLowSurrogate(path.charAt(index + 1))) {
                    throw new IllegalArgumentException("source path must contain valid Unicode");
                }
                index++;
            } else if (Character.isLowSurrogate(character)) {
                throw new IllegalArgumentException("source path must contain valid Unicode");
            } else if (character == '/') {
                requireSegment(path, segmentStart, index);
                segmentStart = index + 1;
            }
        }
        requireSegment(path, segmentStart, path.length());
        return path;
    }

    public static boolean isExcluded(String path) {
        requireDirectory(path);
        int segmentStart = 0;
        for (int index = 0; index <= path.length(); index++) {
            if (index == path.length() || path.charAt(index) == '/') {
                for (String excluded : EXCLUDED_DIRECTORIES) {
                    if (index - segmentStart == excluded.length()
                            && path.regionMatches(segmentStart, excluded, 0, excluded.length())) {
                        return true;
                    }
                }
                int segmentLength = index - segmentStart;
                if (segmentLength >= 6 && path.regionMatches(index - 6, ".class", 0, 6)) {
                    return true;
                }
                segmentStart = index + 1;
            }
        }
        return false;
    }

    private static boolean isDrivePath(String path) {
        if (path.length() < 2 || path.charAt(1) != ':') {
            return false;
        }
        char drive = path.charAt(0);
        return drive >= 'a' && drive <= 'z' || drive >= 'A' && drive <= 'Z';
    }

    private static void requireSegment(String path, int start, int end) {
        int length = end - start;
        if (length == 0 || length == 1 && path.charAt(start) == '.'
                || length == 2 && path.charAt(start) == '.' && path.charAt(start + 1) == '.') {
            throw new IllegalArgumentException("source path must be canonical without traversal");
        }
    }
}
