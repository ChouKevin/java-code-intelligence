package com.java.semantic.semantic.adapter.jdtls;

/**
 * JDT LS 工作區的生命週期狀態
 *
 * STOPPED → STARTING → IMPORTING → READY,任一階段失敗則進入 FAILED
 * IMPORTING 代表 initialize 已完成但符號尚未解析完;此時查詢會拿到空結果,不可視為就緒
 */
public enum SemanticEngineStatus {

    /** 沒有工作區,或已被停止與淘汰 */
    STOPPED,

    /** 已開始啟動程序,尚未完成 initialize */
    STARTING,

    /** initialize 已完成,專案匯入與符號索引進行中 */
    IMPORTING,

    /** 四項就緒條件全部成立 */
    READY,

    /** 啟動或匯入失敗 */
    FAILED
}
