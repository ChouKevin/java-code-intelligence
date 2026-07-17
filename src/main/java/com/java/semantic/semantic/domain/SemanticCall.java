package com.java.semantic.semantic.domain;

import org.springframework.util.Assert;

import java.util.Objects;

/**
 * 某方法內解析到的一筆外呼
 *
 * methodName 是從 JDT 帶簽章的名稱(如「save(T) : void」)剝出的裸名,
 * 純比對裸名會把帶簽章的名稱誤判為未解析,故兩者都保留
 * external 標示目標落在工作區外(jdt: 協定或根目錄之外),Task 7 不應遞迴進去
 */
public record SemanticCall(
        String methodName,
        String rawSignature,
        SemanticLocation target,
        boolean external) {

    public SemanticCall {
        Assert.hasText(methodName, "methodName is required");
        Assert.hasText(rawSignature, "rawSignature is required");
        Objects.requireNonNull(target, "target is required");
    }
}
