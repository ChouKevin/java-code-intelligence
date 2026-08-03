package com.java.semantic.mcp.provider;

import com.java.semantic.mcp.McpQueryRegistration;
import com.java.semantic.mcp.McpToolContractException;
import com.java.semantic.mcp.StrictMcpToolInputDecoder;
import com.java.semantic.mcp.dto.framework.FrameworkDiscoveryMcpDtos;
import com.java.semantic.mcp.dto.source.SourceDiscoveryMcpDtos;
import com.java.semantic.monitoring.MonitoringField;
import com.java.semantic.monitoring.MonitoringMode;
import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;
import com.java.semantic.syntax.application.EventListenerDiscoveryApplicationService;
import com.java.semantic.syntax.application.EventListenerDiscoveryQuery;
import com.java.semantic.syntax.application.RevisionBoundEventListenerDiscovery;
import com.java.semantic.syntax.application.concept.ConceptDiscoveryApplicationService;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

/** 驗證 discovery provider 的 MCP 邊界與原始碼監控契約 */
@SpringBootTest
class DiscoveryMcpToolsTest {

    @Autowired
    private FrameworkDiscoveryMcpTools frameworkTools;

    @Autowired
    private ConceptDiscoveryMcpTools conceptTools;

    @Autowired
    private SourceDiscoveryMcpTools sourceTools;

    @Autowired
    @Qualifier("mcpQueryToolSpecifications")
    private List<McpStatelessServerFeatures.SyncToolSpecification> specifications;

    @MockitoBean
    private EventListenerDiscoveryApplicationService eventListenerDiscoveryApplicationService;

    @MockitoBean
    private ConceptDiscoveryApplicationService conceptDiscoveryApplicationService;

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

    @Test
    void should_invoke_event_listener_discovery_with_the_default_offset_when_omitted() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        StrictMcpToolInputDecoder decoder = new StrictMcpToolInputDecoder(new JsonMapper(), validator);
        given(eventListenerDiscoveryApplicationService.discover(any()))
                .willReturn(mock(RevisionBoundEventListenerDiscovery.class));

        FrameworkDiscoveryMcpDtos.EventListenersInput input = decoder.decode(
                Map.of(
                        "repoId", "orders",
                        "expectedRevision", "FIXTURE",
                        "eventType", "com.example.OrderCreated",
                        "limit", 20),
                FrameworkDiscoveryMcpDtos.EventListenersInput.class);

        invoke(registration(frameworkTools.registrations(), "semantic_discover_event_listeners"), input);

        then(eventListenerDiscoveryApplicationService).should().discover(new EventListenerDiscoveryQuery(
                RepositoryId.of("orders"),
                new RepositoryRevision("FIXTURE"),
                "com.example.OrderCreated",
                0,
                20));
    }

    @Test
    void should_reject_an_invalid_method_identity_before_the_concept_handler_runs() {
        assertInvalidConceptInput(Map.of(
                "kind", "METHOD",
                "target", invalidMethodTarget()));

        then(conceptDiscoveryApplicationService).shouldHaveNoInteractions();
    }

    @Test
    void should_reject_a_method_scoped_field_identity_before_the_concept_handler_runs() {
        assertInvalidConceptInput(Map.of(
                "kind", "FIELD",
                "field", Map.of(
                        "scope", "METHOD",
                        "declaringMethod", methodTarget(),
                        "declarationRange", syntaxRange(),
                        "name", "request")));

        then(conceptDiscoveryApplicationService).shouldHaveNoInteractions();
    }

    private List<String> names(List<McpQueryRegistration<?, ?>> registrations) {
        return registrations.stream().map(McpQueryRegistration::name).toList();
    }

    private void assertRevisionInput(Class<?> inputType) {
        assertThat(inputType.getRecordComponents()).extracting(RecordComponent::getName)
                .contains("repoId", "expectedRevision");
    }

    @SuppressWarnings("unchecked")
    private static <I, O> McpQueryRegistration<I, O> registration(
            List<McpQueryRegistration<?, ?>> registrations,
            String name) {
        return (McpQueryRegistration<I, O>) registrations.stream()
                .filter(registration -> registration.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static <I, O> O invoke(McpQueryRegistration<I, O> registration, I input) {
        return registration.handler().apply(input);
    }

    private void assertInvalidConceptInput(Map<String, Object> identity) {
        McpStatelessServerFeatures.SyncToolSpecification specification = specifications.stream()
                .filter(candidate -> candidate.tool().name().equals("semantic_resolve_concept"))
                .findFirst()
                .orElseThrow();

        assertThatThrownBy(() -> specification.callHandler().apply(
                null,
                new McpSchema.CallToolRequest("semantic_resolve_concept", Map.of(
                        "repoId", "orders",
                        "expectedRevision", "FIXTURE",
                        "identity", identity))))
                .isInstanceOf(McpToolContractException.class)
                .extracting(exception -> ((McpToolContractException) exception).code())
                .isEqualTo("INVALID_TOOL_INPUT");
    }

    private static Map<String, Object> invalidMethodTarget() {
        return Map.of(
                "sourceType", Map.of(
                        "javaType", Map.of("packageName", "com.example", "className", "OrderController"),
                        "sourceFile", "src/main/java/com/example/OrderController.java"),
                "methodName", "not-a-method",
                "parameterTypes", List.of());
    }

    private static Map<String, Object> methodTarget() {
        return Map.of(
                "sourceType", Map.of(
                        "javaType", Map.of("packageName", "com.example", "className", "OrderController"),
                        "sourceFile", "src/main/java/com/example/OrderController.java"),
                "methodName", "getOrder",
                "parameterTypes", List.of("java.lang.String"));
    }

    private static Map<String, Object> syntaxRange() {
        return Map.of(
                "start", Map.of("line", 10, "character", 4),
                "end", Map.of("line", 10, "character", 11));
    }
}
