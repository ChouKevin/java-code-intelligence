package com.java.semantic.mcp.provider;

import com.java.semantic.mcp.McpQueryRegistration;
import com.java.semantic.mcp.dto.source.SourceDiscoveryMcpDtos;
import com.java.semantic.monitoring.MonitoringField;
import com.java.semantic.monitoring.MonitoringMode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.lang.reflect.RecordComponent;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 驗證 discovery provider 的 MCP 邊界與原始碼監控契約 */
@SpringBootTest
class DiscoveryMcpToolsTest {

    @Autowired
    private FrameworkDiscoveryMcpTools frameworkTools;

    @Autowired
    private ConceptDiscoveryMcpTools conceptTools;

    @Autowired
    private SourceDiscoveryMcpTools sourceTools;

    @Test
    void should_register_the_ten_discovery_queries_with_stateless_revision_inputs() {
        assertThat(names(frameworkTools.registrations())).containsExactly(
                "semantic_discover_event_listeners", "semantic_discover_method_implementations");
        assertThat(names(conceptTools.registrations())).containsExactly(
                "semantic_discover_concepts", "semantic_resolve_concept", "semantic_discover_type_members");
        assertThat(names(sourceTools.registrations())).containsExactly(
                "semantic_resolve_source_symbol", "semantic_find_internal_references", "semantic_get_source_segment",
                "semantic_get_method_source", "semantic_get_evidence_source");

        for (McpQueryRegistration<?, ?> registration : frameworkTools.registrations()) {
            assertRevisionInput(registration.inputType());
        }
        for (McpQueryRegistration<?, ?> registration : conceptTools.registrations()) {
            assertRevisionInput(registration.inputType());
        }
        for (McpQueryRegistration<?, ?> registration : sourceTools.registrations()) {
            assertRevisionInput(registration.inputType());
        }
    }

    @Test
    void should_omit_source_bodies_from_mcp_monitoring_projections() {
        assertThat(SourceDiscoveryMcpDtos.SourceSegmentOutput.class.getRecordComponents())
                .filteredOn(component -> component.getName().equals("content"))
                .extracting(component -> component.getAnnotation(MonitoringField.class).value())
                .containsExactly(MonitoringMode.OMIT);
        assertThat(SourceDiscoveryMcpDtos.SourceSegment.class.getRecordComponents())
                .filteredOn(component -> component.getName().equals("content"))
                .extracting(component -> component.getAnnotation(MonitoringField.class).value())
                .containsExactly(MonitoringMode.OMIT);
    }

    private List<String> names(List<McpQueryRegistration<?, ?>> registrations) {
        return registrations.stream().map(McpQueryRegistration::name).toList();
    }

    private void assertRevisionInput(Class<?> inputType) {
        assertThat(inputType.getRecordComponents()).extracting(RecordComponent::getName)
                .contains("repoId", "expectedRevision");
    }
}
