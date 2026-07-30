package com.java.semantic.semantic.domain;

import com.java.semantic.repository.domain.RepositorySnapshot;

import java.util.List;

/**
 * 以 JDT LS 回答真正的語意問題:解析方法、找外呼、找實作
 *
 * 這是 Task 7 呼叫圖的地基;所有回傳型別皆不含 LSP4J,轉換只在 adapter 內發生
 */
public interface JavaSemanticService {

    /** Resolves a syntax-proven declaration through the semantic engine. */
    SemanticMethod resolveExactMethod(RepositorySnapshot snapshot, SemanticDeclarationAnchor anchor);

    /** Resolves a descendant call without discarding ambiguity evidence. */
    SemanticCallResolution resolveCallResolutionAt(
            RepositorySnapshot snapshot, SemanticMethod caller, SemanticCallSite callSite);

    /** 回傳方法內解析到的外呼,依 URI 與區間去重 */
    List<SemanticCall> outgoingCalls(RepositorySnapshot snapshot, SemanticMethod method);

    /** Returns locally resolvable callers and sanitized conversion issues. */
    SemanticIncomingCallResult incomingCalls(RepositorySnapshot snapshot, SemanticMethod callee);

    /** 回傳方法的所有實作與逐一位置轉換時保留的問題證據 */
    SemanticImplementationResult implementations(RepositorySnapshot snapshot, SemanticMethod method);
}
