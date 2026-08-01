package com.java.semantic.syntax.domain;

/** 原始碼中的零基座標 */
public record SyntaxPosition(int line, int character) {

    public SyntaxPosition {
        require(line >= 0, "line must not be negative");
        require(character >= 0, "character must not be negative");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
