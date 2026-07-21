package com.java.semantic.callgraph.application;

import com.java.semantic.callgraph.domain.TypeId;
import org.springframework.util.Assert;

import java.util.Objects;

/** 將來源切片與不可分離的標準型別識別配對 */
public record RelatedClassEvidence(TypeId typeId, String source) {

    public RelatedClassEvidence {
        Objects.requireNonNull(typeId, "typeId is required");
        Assert.notNull(source, "source is required");
    }

    String canonicalName() {
        return typeId.fullyQualifiedName();
    }
}
