package com.java.semantic.semantic.domain;

import org.springframework.util.Assert;

/**
 * 原始碼中的座標,行與欄皆為零基
 *
 * 對齊 LSP 的座標系,轉為 1 基只在對外邊界進行
 */
public record SemanticPosition(int line, int character) {

    public SemanticPosition {
        Assert.isTrue(line >= 0, "line must not be negative");
        Assert.isTrue(character >= 0, "character must not be negative");
    }
}
