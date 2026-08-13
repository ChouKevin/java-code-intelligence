package com.java.semantic.syntax.application;

import java.util.List;

/** 型別成員分頁中的封閉成員變體 */
public sealed interface TypeMember permits MethodTypeMember, FieldTypeMember, EnumConstantTypeMember,
        RecordComponentTypeMember {

    /** 回傳固定的成員 discriminator */
    TypeMemberKind kind();

    /** 回傳不需 Agent 重建參數的可執行後續請求 */
    List<DiscoveryFollowUp> availableFollowUps();
}
