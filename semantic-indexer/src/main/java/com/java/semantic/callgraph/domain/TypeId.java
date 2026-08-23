package com.java.semantic.callgraph.domain;

import com.java.semantic.identity.JavaIdentityNormalizer;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/** 供型別層級讀取政策使用的儲存庫範圍 Java 正規型別識別 */
public record TypeId(String repoId, String packageName, String className) {

    public TypeId {
        Assert.hasText(repoId, "repoId is required");
        Assert.notNull(packageName, "packageName is required");
        Assert.hasText(className, "className is required");
        className = JavaIdentityNormalizer.className(packageName, className);
    }

    public String fullyQualifiedName() {
        return StringUtils.hasText(packageName) ? packageName + "." + className : className;
    }
}
