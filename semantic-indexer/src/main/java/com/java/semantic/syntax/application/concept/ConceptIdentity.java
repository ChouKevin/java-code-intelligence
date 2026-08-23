package com.java.semantic.syntax.application.concept;

/**
 * 概念唯一識別，等值比較是唯一可用的 catalog 合併依據
 * HTTP transport 的 target 與 subject 僅由這些型別化識別衍生
 */
public sealed interface ConceptIdentity permits
        DeclarationConceptIdentity,
        UsageConceptIdentity,
        EntryPointConceptIdentity,
        MapperConceptIdentity {

    /** 概念的業務種類 */
    ConceptKind kind();

    /** 精確 identity 變體，供合併證據與排序區分同一概念種類的不同實體 */
    ConceptIdentityKind identityKind();

}
