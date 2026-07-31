package com.java.semantic.syntax.application;

import java.util.Objects;

import com.java.semantic.identity.MethodTarget;

import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/** 概念識別共用的文字驗證與 canonical 目標格式 */
final class ConceptIdentitySupport {

    private ConceptIdentitySupport() {
    }

    static String requiredText(String value, String fieldName) {
        String text = Objects.requireNonNull(value, fieldName + " is required");
        Assert.isTrue(StringUtils.hasText(text), fieldName + " is required");
        return text;
    }

    static String methodTargetKey(MethodTarget target) {
        return target.sourceFile() + "|" + target.packageName() + "|" + target.className() + "|"
                + target.methodName() + "|" + String.join(",", target.parameterTypes());
    }

    static String methodDisplayValue(MethodTarget target) {
        String qualifiedType = target.packageName().isEmpty()
                ? target.className()
                : target.packageName() + "." + target.className();
        return qualifiedType + "#" + target.methodName() + "(" + String.join(",", target.parameterTypes()) + ")";
    }
}
