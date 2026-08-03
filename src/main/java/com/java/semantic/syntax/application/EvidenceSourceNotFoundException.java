package com.java.semantic.syntax.application;

/** 指定 typed evidence 不在 revision-pinned syntax snapshot 中 */
public final class EvidenceSourceNotFoundException extends RuntimeException {

    public EvidenceSourceNotFoundException() {
        super("evidence source was not found");
    }
}
