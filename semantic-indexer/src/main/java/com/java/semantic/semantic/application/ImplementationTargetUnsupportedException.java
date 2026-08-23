package com.java.semantic.semantic.application;

import com.java.semantic.model.codefact.MethodTarget;

import java.util.Objects;

/** 請求目標不是可探索實作的抽象宣告 */
public final class ImplementationTargetUnsupportedException extends IllegalArgumentException {

    private final MethodTarget target;

    public ImplementationTargetUnsupportedException(MethodTarget target) {
        super("implementation discovery requires an abstract declaration target");
        this.target = Objects.requireNonNull(target, "target is required");
    }

    public MethodTarget target() {
        return target;
    }
}
