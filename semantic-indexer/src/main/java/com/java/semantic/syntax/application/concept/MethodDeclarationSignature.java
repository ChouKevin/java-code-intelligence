package com.java.semantic.syntax.application.concept;

import java.util.List;
import java.util.Objects;

import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/** 未解析方法宣告的原始簽名，供 usage identity 保留不猜測的參數證據 */
public record MethodDeclarationSignature(String methodName, List<String> parameterTypes) {

    public MethodDeclarationSignature {
        methodName = requiredText(methodName, "methodName");
        parameterTypes = List.copyOf(Objects.requireNonNull(parameterTypes, "parameterTypes are required"));
        for (String parameterType : parameterTypes) {
            requiredText(parameterType, "parameterTypes must not contain blank values");
        }
    }

    /** 回傳不影響 identity 的原始簽名顯示 */
    public String displayValue() {
        return methodName + "(" + String.join(",", parameterTypes) + ")";
    }

    private static String requiredText(String value, String fieldName) {
        String text = Objects.requireNonNull(value, fieldName + " is required");
        Assert.isTrue(StringUtils.hasText(text), fieldName + " is required");
        return text;
    }
}
