package com.java.semantic.semantic.domain;

import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/** 工作區內有多個不同來源宣告同一個完整型別名稱 */
public final class SemanticAmbiguousTypeException extends RuntimeException {

    public SemanticAmbiguousTypeException(String packageName, String className) {
        super(message(packageName, className));
    }

    private static String message(String packageName, String className) {
        Assert.notNull(packageName, "packageName is required");
        Assert.hasText(className, "className is required");
        String qualifiedName = StringUtils.hasText(packageName)
                ? packageName + "." + className
                : className;
        return "type " + qualifiedName + " is ambiguous";
    }
}
