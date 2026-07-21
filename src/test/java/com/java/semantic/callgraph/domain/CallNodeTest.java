package com.java.semantic.callgraph.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CallNodeTest {

    @Test
    void should_reject_method_identity_when_business_read_is_forbidden() {
        assertThatThrownBy(() -> node(methodId(), EvidenceVisibility.BUSINESS_READ_FORBIDDEN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("methodId must be absent when business read is forbidden");
    }

    @Test
    void should_require_method_identity_when_business_read_is_allowed() {
        assertThatThrownBy(() -> node(null, EvidenceVisibility.READABLE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("methodId is required when business read is allowed");
    }

    private static CallNode node(MethodId methodId, EvidenceVisibility visibility) {
        return new CallNode(
                new CallNodeId("node-1"),
                methodId,
                "OrderService.place()",
                CallType.INTERNAL_SERVICE,
                "src/main/java/com/acme/OrderService.java",
                10,
                20,
                Map.of(),
                "void place() {}",
                visibility);
    }

    private static MethodId methodId() {
        return new MethodId("orders", "com.acme", "OrderService", "place", List.of());
    }
}
