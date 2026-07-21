package com.java.semantic.callgraph.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.springframework.util.Assert;

import java.util.List;
import java.util.Objects;

public record CallEdge(
        CallNodeId caller,
        CallNodeId callee,
        String callExpression,
        CallSiteRange callSite,
        ResolutionStrategy resolutionStrategy,
        double confidence,
        List<String> evidence,
        List<String> warnings,
        EvidenceVisibility visibility) {

    public CallEdge {
        Assert.notNull(caller, "caller is required");
        Assert.notNull(callee, "callee is required");
        Assert.notNull(resolutionStrategy, "resolutionStrategy is required");
        Assert.notNull(visibility, "visibility is required");
        if (EvidenceVisibility.BUSINESS_READ_FORBIDDEN.equals(visibility)) {
            Assert.isNull(callSite, "forbidden edge must not expose a call site");
        }
        evidence = List.copyOf(evidence);
        warnings = List.copyOf(warnings);
    }

    /** 舊投影僅有行號，不能表達完整區間；新程式應改讀 callSite。 */
    @JsonIgnore
    public String sourceFile() {
        return Objects.nonNull(callSite) ? callSite.sourceFile() : "";
    }

    /** 舊投影僅有起始行號；新程式應改讀 callSite。 */
    @JsonIgnore
    public Integer lineNumber() {
        return Objects.nonNull(callSite) ? callSite.startLine() : null;
    }
}
