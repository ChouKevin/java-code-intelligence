package com.java.semantic.api;

import com.java.semantic.api.dto.MethodTargetRequest;
import com.java.semantic.api.dto.MethodTargetResponse;
import com.java.semantic.identity.JavaTypeIdentity;
import com.java.semantic.identity.MethodTarget;
import com.java.semantic.identity.SourceTypeIdentity;

import java.util.Objects;

/** 將扁平 HTTP MethodTarget 與組合的來源識別模型互相轉換 */
public final class MethodTargetHttpMapper {

    private MethodTargetHttpMapper() {
    }

    /** 將已驗證的扁平 HTTP 請求轉換為組合的領域目標 */
    public static MethodTarget toDomain(MethodTargetRequest request) {
        MethodTargetRequest httpRequest = Objects.requireNonNull(request, "request is required");
        return new MethodTarget(
                new SourceTypeIdentity(
                        new JavaTypeIdentity(httpRequest.packageName(), httpRequest.className()),
                        httpRequest.sourceFile()),
                httpRequest.methodName(),
                httpRequest.parameterTypes());
    }

    /** 將組合的領域目標轉換為穩定的扁平 HTTP 回應 */
    public static MethodTargetResponse toResponse(MethodTarget target) {
        MethodTarget domainTarget = Objects.requireNonNull(target, "target is required");
        SourceTypeIdentity sourceType = domainTarget.sourceType();
        JavaTypeIdentity javaType = sourceType.javaType();
        return new MethodTargetResponse(
                sourceType.sourceFile(),
                javaType.packageName(),
                javaType.className(),
                domainTarget.methodName(),
                domainTarget.parameterTypes());
    }
}
