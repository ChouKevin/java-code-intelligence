package com.java.semantic.syntax.domain;

import com.java.semantic.model.codefact.MethodTarget;

import java.util.Objects;

/** 相同 canonical 方法目標出現重複 exact declaration proof 的語法契約錯誤 */
public final class DuplicateMethodDeclarationProofException extends IllegalStateException {

    private final MethodTarget target;
    private final int proofCount;

    public DuplicateMethodDeclarationProofException(MethodTarget target, int proofCount) {
        super("duplicate exact method declaration proofs violate syntax aggregation contract");
        this.target = Objects.requireNonNull(target, "target is required");
        if (proofCount < 2) {
            throw new IllegalArgumentException("proofCount must contain duplicate proofs");
        }
        this.proofCount = proofCount;
    }

    public MethodTarget target() {
        return target;
    }

    public int proofCount() {
        return proofCount;
    }
}
