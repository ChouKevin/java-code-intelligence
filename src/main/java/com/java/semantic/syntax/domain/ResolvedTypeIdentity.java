package com.java.semantic.syntax.domain;

import com.java.semantic.identity.PolicyIdentity;
import org.springframework.util.Assert;

/** JDT binding 已證實的宣告型別識別。 */
public record ResolvedTypeIdentity(String packageName, String className) {

    public ResolvedTypeIdentity {
        Assert.notNull(packageName, "packageName is required");
        Assert.hasText(className, "className is required");
        className = PolicyIdentity.className(packageName, className);
    }
}
