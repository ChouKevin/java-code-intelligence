package com.java.semantic.syntax.domain;

import org.springframework.util.Assert;

/** 原始碼中的零基座標 */
public record SyntaxPosition(int line, int character) {

    public SyntaxPosition {
        Assert.isTrue(line >= 0, "line must not be negative");
        Assert.isTrue(character >= 0, "character must not be negative");
    }
}
