package com.java.semantic.identity;

import java.util.Objects;

import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/** Validates repository-root-relative Java source paths normalized to forward slashes. */
public final class RepositoryRelativeSource {

    private static final int MAX_SOURCE_FILE_LENGTH = 1024;

    private RepositoryRelativeSource() {
        throw new UnsupportedOperationException("utility class");
    }

    public static String requireValid(String sourceFile) {
        Objects.requireNonNull(sourceFile, "repository-relative source is required");
        Assert.isTrue(StringUtils.hasText(sourceFile), "repository-relative source must not be blank");
        Assert.isTrue(sourceFile.length() <= MAX_SOURCE_FILE_LENGTH,
                "repository-relative source must not exceed 1024 characters");
        Assert.isTrue(sourceFile.charAt(0) != '/', "repository-relative source must not be absolute");
        Assert.isTrue(!sourceFile.contains("\\"), "repository-relative source must use forward slashes");
        Assert.isTrue(!sourceFile.contains(":"), "repository-relative source must not contain a scheme or drive");
        Assert.isTrue(sourceFile.codePoints().noneMatch(character -> Character.isWhitespace(character)
                || Character.isSpaceChar(character) || Character.isISOControl(character)),
                "repository-relative source must not contain whitespace or control characters");
        for (String segment : sourceFile.split("/", -1)) {
            Assert.isTrue(StringUtils.hasText(segment) && !".".equals(segment) && !"..".equals(segment),
                    "repository-relative source contains an invalid segment");
        }
        return sourceFile;
    }
}
