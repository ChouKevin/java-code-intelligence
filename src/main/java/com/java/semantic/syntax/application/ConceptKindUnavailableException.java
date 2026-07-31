package com.java.semantic.syntax.application;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 要求尚未由目前 composition 啟用的已宣告概念種類 */
public final class ConceptKindUnavailableException extends RuntimeException {

    private final List<ConceptKind> unavailableKinds;
    private final List<ConceptKind> supportedKinds;

    public ConceptKindUnavailableException(Set<ConceptKind> unavailableKinds, Set<ConceptKind> supportedKinds) {
        super("requested concept kind is unavailable");
        this.unavailableKinds = ordered(unavailableKinds, "unavailableKinds");
        this.supportedKinds = ordered(supportedKinds, "supportedKinds");
    }

    /** 回傳本次拒絕的已宣告未啟用種類 */
    public List<ConceptKind> unavailableKinds() {
        return unavailableKinds;
    }

    /** 回傳目前組態可接受的已啟用種類 */
    public List<ConceptKind> supportedKinds() {
        return supportedKinds;
    }

    private static List<ConceptKind> ordered(Set<ConceptKind> kinds, String name) {
        return Objects.requireNonNull(kinds, name + " is required").stream().sorted().toList();
    }
}
