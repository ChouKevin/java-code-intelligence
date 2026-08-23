package com.java.semantic.api;

import com.java.semantic.api.dto.GraphNodeResponse;
import com.java.semantic.callgraph.domain.GraphNode;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;

import static org.assertj.core.api.Assertions.assertThat;

/** 驗證 call graph 節點不會重新暴露內嵌方法原始碼 */
class GraphNodeEmbeddedMethodBodyContractTest {

    @Test
    void should_not_expose_embedded_method_body_from_domain_or_http_contract() {
        assertDoesNotExposeEmbeddedMethodBody(GraphNode.class);
        assertDoesNotExposeEmbeddedMethodBody(GraphNodeResponse.class);
    }

    private static void assertDoesNotExposeEmbeddedMethodBody(Class<?> graphNodeType) {
        assertThat(graphNodeType.getRecordComponents())
                .extracting(RecordComponent::getName)
                .doesNotContain("methodBody");
        assertThat(graphNodeType.getMethods())
                .filteredOn(method -> method.getParameterCount() == 0)
                .extracting(Method::getName)
                .doesNotContain("methodBody");
    }
}
