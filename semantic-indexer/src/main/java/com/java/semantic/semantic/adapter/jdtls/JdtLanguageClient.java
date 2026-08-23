package com.java.semantic.semantic.adapter.jdtls;

import org.eclipse.lsp4j.RegistrationParams;
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification;
import org.eclipse.lsp4j.services.LanguageClient;

import java.util.concurrent.CompletableFuture;

/** 宣告 JDT Language Server 專用通知 */
public interface JdtLanguageClient extends LanguageClient {

    /** 接收語言伺服器狀態 */
    @JsonNotification("language/status")
    void languageStatus(StatusReport report);

    /** 接收語言伺服器事件 */
    @JsonNotification("language/eventNotification")
    void languageEvent(Object event);

    /** 接受伺服器動態註冊能力 */
    @Override
    default CompletableFuture<Void> registerCapability(RegistrationParams params) {
        return CompletableFuture.completedFuture(null);
    }
}
