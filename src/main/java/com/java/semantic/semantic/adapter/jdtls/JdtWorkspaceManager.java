package com.java.semantic.semantic.adapter.jdtls;

import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositorySnapshot;

/** 管理每個儲存庫的 JDT LS 工作區生命週期 */
public interface JdtWorkspaceManager {

    /**
     * 取得已就緒的工作區,必要時啟動並等待匯入完成
     *
     * 必須在 B1 讀鎖內呼叫,snapshot 的版本會綁定到 session
     */
    JdtWorkspaceSession getOrStart(RepositorySnapshot snapshot);

    /** 回報工作區狀態,未啟動的儲存庫回傳 STOPPED */
    SemanticEngineStatus status(RepositoryId repositoryId);

    /** 停止並移除工作區,工作樹即將變更時呼叫 */
    void invalidate(RepositoryId repositoryId);

    /** 停止所有工作區 */
    void shutdownAll();
}
