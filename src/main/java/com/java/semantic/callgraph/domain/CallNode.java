package com.java.semantic.callgraph.domain;

import org.springframework.util.Assert;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record CallNode(
        CallNodeId nodeId,
        MethodId methodId,
        String signature,
        CallType callType,
        String sourceFile,
        Integer startLine,
        Integer endLine,
        Map<String, String> annotations,
        String code,
        EvidenceVisibility visibility) {

    public CallNode {
        Assert.notNull(visibility, "visibility is required");
        if (EvidenceVisibility.BUSINESS_READ_FORBIDDEN.equals(visibility)) {
            Assert.isNull(methodId, "methodId must be absent when business read is forbidden");
        } else if (!CallType.UNRESOLVED.equals(callType)) {
            Assert.notNull(methodId, "methodId is required when business read is allowed");
        }
        annotations = Collections.unmodifiableMap(new LinkedHashMap<>(annotations));
    }
}
