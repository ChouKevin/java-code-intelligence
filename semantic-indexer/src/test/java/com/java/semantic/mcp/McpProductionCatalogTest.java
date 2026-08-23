package com.java.semantic.mcp;

import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 驗證 production application context 只投影完整的 canonical MCP 查詢目錄 */
@SpringBootTest
class McpProductionCatalogTest {

    @Autowired
    @Qualifier("mcpQueryToolSpecifications")
    private List<McpStatelessServerFeatures.SyncToolSpecification> specifications;

    @Test
    void should_project_exactly_the_seventeen_canonical_production_tools() {
        assertThat(specifications)
                .extracting(specification -> specification.tool().name())
                .containsExactlyElementsOf(McpQueryRegistry.canonicalToolNames());
    }
}
