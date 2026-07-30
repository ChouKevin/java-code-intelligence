package com.java.semantic.semantic.application;

import com.java.semantic.identity.MethodTarget;

import java.util.Objects;

/** canonical target 缺少必要的語法 metadata，無法安全回傳候選 */
public final class ImplementationDiscoveryContractException extends IllegalStateException {

    private final MethodTarget target;

    public ImplementationDiscoveryContractException(MethodTarget target) {
        super("implementation candidate is missing syntax metadata");
        this.target = Objects.requireNonNull(target, "target is required");
    }

    public MethodTarget target() {
        return target;
    }
}
