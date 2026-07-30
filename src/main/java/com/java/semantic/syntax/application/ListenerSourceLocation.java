package com.java.semantic.syntax.application;

import com.java.semantic.syntax.domain.SyntaxPosition;
import com.java.semantic.syntax.domain.SyntaxRange;
import org.springframework.util.Assert;

import java.util.Objects;

/** 監聽器方法在儲存庫原始碼中的位置 */
public record ListenerSourceLocation(String sourceFile, SyntaxPosition start, SyntaxPosition end) {

    public ListenerSourceLocation {
        Assert.hasText(sourceFile, "sourceFile is required");
        Objects.requireNonNull(start, "start is required");
        Objects.requireNonNull(end, "end is required");
    }

    static ListenerSourceLocation from(String sourceFile, SyntaxRange range) {
        Objects.requireNonNull(range, "range is required");
        return new ListenerSourceLocation(sourceFile, range.start(), range.end());
    }
}
