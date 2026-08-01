package com.java.semantic.identity;

import java.util.Objects;

/** 驗證正規化為正斜線的儲存庫根目錄相對 Java 來源路徑 */
public final class RepositoryRelativeSource {

    private static final int MAX_SOURCE_FILE_LENGTH = 1024;

    private RepositoryRelativeSource() {
        throw new UnsupportedOperationException("utility class");
    }

    public static String requireValid(String sourceFile) {
        Objects.requireNonNull(sourceFile, "repository-relative source is required");
        require(!sourceFile.isBlank(), "repository-relative source must not be blank");
        require(sourceFile.length() <= MAX_SOURCE_FILE_LENGTH,
                "repository-relative source must not exceed 1024 characters");
        require(sourceFile.charAt(0) != '/', "repository-relative source must not be absolute");
        require(!sourceFile.contains("\\"), "repository-relative source must use forward slashes");
        require(!sourceFile.contains(":"), "repository-relative source must not contain a scheme or drive");
        require(sourceFile.codePoints().noneMatch(character -> Character.isWhitespace(character)
                || Character.isSpaceChar(character) || Character.isISOControl(character)),
                "repository-relative source must not contain whitespace or control characters");
        for (String segment : sourceFile.split("/", -1)) {
            require(!segment.isBlank() && !".".equals(segment) && !"..".equals(segment),
                    "repository-relative source contains an invalid segment");
        }
        return sourceFile;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
