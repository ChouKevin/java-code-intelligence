package com.java.semantic.semantic.domain;

import org.springframework.util.Assert;

import java.util.Objects;

/**
 * 語意元素在工作區中的位置
 *
 * selectionRange 指向識別符本身(方法名),是呼叫階層查詢的錨點;range 涵蓋整個宣告
 */
public record SemanticLocation(String uri, SemanticRange range, SemanticRange selectionRange) {

    public SemanticLocation {
        Assert.hasText(uri, "uri is required");
        Objects.requireNonNull(range, "range is required");
        Objects.requireNonNull(selectionRange, "selectionRange is required");
    }
}
