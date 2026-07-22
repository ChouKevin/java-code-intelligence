package com.java.semantic.semantic.adapter.jdtls;

import org.eclipse.lsp4j.jsonrpc.services.JsonRequest;
import org.eclipse.lsp4j.services.LanguageServer;

import java.util.concurrent.CompletableFuture;

/** JDT LS 私有 protocol extension，避免將伺服器特有請求洩漏到一般 LSP 介面。 */
interface JdtLsLanguageServer extends LanguageServer {

    @JsonRequest("java/buildWorkspace")
    CompletableFuture<JdtLsBuildWorkspaceStatus> buildWorkspace(Boolean forceRebuild);
}
