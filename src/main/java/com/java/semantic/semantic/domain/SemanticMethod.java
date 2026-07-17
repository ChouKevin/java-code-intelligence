package com.java.semantic.semantic.domain;

import org.springframework.util.Assert;

import java.util.List;
import java.util.Objects;

/**
 * JDT LS 精確解析出的方法
 *
 * parameterTypes 為正規化後的簡單型別名(去除泛型與套件前綴),用來區分多載
 * location 由此方法後續可再問出外呼與實作,而不需重新解析
 */
public record SemanticMethod(
        String packageName,
        String className,
        String methodName,
        List<String> parameterTypes,
        String returnType,
        SemanticLocation location) {

    public SemanticMethod {
        Objects.requireNonNull(packageName, "packageName is required");
        Assert.hasText(className, "className is required");
        Assert.hasText(methodName, "methodName is required");
        parameterTypes = List.copyOf(Objects.requireNonNull(parameterTypes, "parameterTypes is required"));
        returnType = Objects.requireNonNullElse(returnType, "");
        Objects.requireNonNull(location, "location is required");
    }
}
