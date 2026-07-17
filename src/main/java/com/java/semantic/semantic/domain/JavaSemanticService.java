package com.java.semantic.semantic.domain;

import com.java.semantic.repository.domain.RepositorySnapshot;

import java.util.List;

/**
 * 以 JDT LS 回答真正的語意問題:解析方法、找外呼、找實作
 *
 * 這是 Task 7 呼叫圖的地基;所有回傳型別皆不含 LSP4J,轉換只在 adapter 內發生
 */
public interface JavaSemanticService {

    /**
     * 以精確的套件、類別、方法簽章解析出方法
     *
     * methodSignature 可為裸名或帶參數的簽章;裸名對應多個多載時丟出 SemanticAmbiguousMethodException,
     * 找不到時丟出 SemanticSymbolNotFoundException
     */
    SemanticMethod resolveMethod(
            RepositorySnapshot snapshot, String packageName, String className, String methodSignature);

    /** 回傳方法內解析到的外呼,依 URI 與區間去重 */
    List<SemanticCall> outgoingCalls(RepositorySnapshot snapshot, SemanticMethod method);

    /** 回傳方法的所有實作,依 URI 與區間去重 */
    List<SemanticMethod> implementations(RepositorySnapshot snapshot, SemanticMethod method);
}
