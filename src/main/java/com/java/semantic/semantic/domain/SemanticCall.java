package com.java.semantic.semantic.domain;

import org.springframework.util.Assert;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 某方法內解析到的一筆外呼
 *
 * target 保留 JDT 解析出的完整方法身分,rawSignature 保留 JDT 原始簽章,
 * callSites 保留呼叫端中所有對應位置
 * external 標示目標落在工作區外(jdt: 協定或根目錄之外),Task 7 不應遞迴進去
 */
public record SemanticCall(
        Optional<SemanticMethod> target,
        String rawSignature,
        List<SemanticRange> callSites,
        boolean external,
        SemanticResolutionOrigin origin,
        SemanticCallStatus status) {

    public SemanticCall {
        target = Objects.requireNonNull(target, "target is required");
        Assert.hasText(rawSignature, "rawSignature is required");
        callSites = List.copyOf(Objects.requireNonNull(callSites, "callSites is required"));
        Objects.requireNonNull(origin, "origin is required");
        Objects.requireNonNull(status, "status is required");
        Assert.isTrue(target.isPresent() == SemanticCallStatus.RESOLVED.equals(status),
                "resolved status must match target presence");
    }

    /** 依目標是否存在建立既有語意呼叫狀態 */
    public SemanticCall(
            Optional<SemanticMethod> target,
            String rawSignature,
            List<SemanticRange> callSites,
            boolean external,
            SemanticResolutionOrigin origin) {
        this(target, rawSignature, callSites, external, origin,
                target.isPresent() ? SemanticCallStatus.RESOLVED : SemanticCallStatus.IDENTITY_UNPROVEN);
    }
}
