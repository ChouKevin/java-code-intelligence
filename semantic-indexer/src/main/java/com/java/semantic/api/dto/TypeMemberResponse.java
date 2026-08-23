package com.java.semantic.api.dto;

/** 型別成員 HTTP 回應的封閉 discriminator 契約 */
public sealed interface TypeMemberResponse permits MethodTypeMemberResponse, FieldTypeMemberResponse {

    /** 固定 METHOD 或 FIELD discriminator */
    String kind();
}
