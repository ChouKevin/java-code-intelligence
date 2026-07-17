package com.java.semantic.semantic.domain;

import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;

import java.util.List;

/**
 * 裸方法名對應到多個多載,無法唯一解析
 *
 * 帶著候選簽章讓工具層可以回頭要求完整簽章,而不是任選第一個同名或同參數個數的方法
 */
public final class SemanticAmbiguousMethodException extends RuntimeException {

    private final transient List<String> candidates;

    public SemanticAmbiguousMethodException(
            String packageName, String className, String methodName, List<String> candidates) {
        super(message(packageName, className, methodName, candidates));
        Assert.notEmpty(candidates, "candidates must not be empty");
        this.candidates = List.copyOf(candidates);
    }

    /** 造成歧義的候選簽章 */
    public List<String> candidates() {
        return candidates;
    }

    private static String message(
            String packageName, String className, String methodName, List<String> candidates) {
        String signatures = CollectionUtils.isEmpty(candidates) ? "" : String.join(", ", candidates);
        return "method " + packageName + "." + className + "#" + methodName
                + " is ambiguous; candidates: " + signatures;
    }
}
