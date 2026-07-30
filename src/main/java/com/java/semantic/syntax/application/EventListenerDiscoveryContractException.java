package com.java.semantic.syntax.application;

import java.util.Objects;

/** 已解析事件監聽器 target 與語法 metadata 違反同一來源檔案契約 */
public final class EventListenerDiscoveryContractException extends IllegalStateException {

    private final String metadataSourceFile;
    private final String targetSourceFile;

    public EventListenerDiscoveryContractException(String metadataSourceFile, String targetSourceFile) {
        super("resolved listener target source file does not match syntax metadata");
        this.metadataSourceFile = Objects.requireNonNull(metadataSourceFile, "metadataSourceFile is required");
        this.targetSourceFile = Objects.requireNonNull(targetSourceFile, "targetSourceFile is required");
    }

    public String metadataSourceFile() {
        return metadataSourceFile;
    }

    public String targetSourceFile() {
        return targetSourceFile;
    }
}
