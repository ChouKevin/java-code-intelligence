package com.java.semantic.mcp;

import com.java.semantic.mcp.dto.concept.ConceptDiscoveryMcpDtos;
import com.java.semantic.mcp.dto.identity.McpJavaIdentityPayloads.SourceMember.MethodScoped;
import com.java.semantic.mcp.dto.source.SourceDiscoveryMcpDtos;
import com.java.semantic.mcp.dto.source.McpExactSourceDeclarationTargetPayload.Member;
import com.java.semantic.mcp.mapper.ConceptDiscoveryMcpMapper;
import com.java.semantic.mcp.mapper.SourceDiscoveryMcpMapper;
import com.java.semantic.syntax.application.EvidenceSourceQuery;
import com.java.semantic.syntax.application.concept.EntryPointConceptIdentity.ApiRouteConceptIdentity;
import com.java.semantic.syntax.application.concept.EntryPointConceptIdentity.ScheduleConceptIdentity;
import com.java.semantic.syntax.application.concept.MapperConceptIdentity.MapperStatementVariantEvidenceIdentity;
import com.java.semantic.syntax.domain.ExactSourceDeclarationTarget;
import com.java.semantic.syntax.domain.SourceRange;
import com.java.semantic.syntax.domain.SyntaxPosition;
import com.java.semantic.syntax.domain.SyntaxRange;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 驗證 discovery MCP 輸入可用封閉 transport identity 解碼 */
class McpDiscoveryInputDecodingTest {

    private StrictMcpToolInputDecoder decoder;

    @BeforeEach
    void setUp() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        decoder = new StrictMcpToolInputDecoder(new JsonMapper(), validator);
    }

    @Test
    void should_decode_representative_concept_target_and_evidence_identity_inputs() {
        ConceptDiscoveryMcpDtos.ResolveInput concept = decoder.decode(
                conceptResolveArguments(), ConceptDiscoveryMcpDtos.ResolveInput.class);
        SourceDiscoveryMcpDtos.InternalReferencesInput references = decoder.decode(
                internalReferenceArguments(), SourceDiscoveryMcpDtos.InternalReferencesInput.class);
        SourceDiscoveryMcpDtos.EvidenceSourceInput evidence = decoder.decode(
                evidenceSourceArguments(), SourceDiscoveryMcpDtos.EvidenceSourceInput.class);

        assertThat(new ConceptDiscoveryMcpMapper().toDomain(concept.identity()))
                .isInstanceOf(ApiRouteConceptIdentity.class);
        assertThat(new SourceDiscoveryMcpMapper().toDomain(references.target()))
                .isInstanceOf(ExactSourceDeclarationTarget.Member.class);
        assertThat(new SourceDiscoveryMcpMapper().toDomain(evidence.identity()))
                .isInstanceOf(EvidenceSourceQuery.MapperFragment.class);
    }

    @Test
    void should_decode_optional_schedule_and_mapper_identity_values_when_omitted() {
        ConceptDiscoveryMcpDtos.ResolveInput schedule = decoder.decode(
                scheduleConceptResolveArguments(), ConceptDiscoveryMcpDtos.ResolveInput.class);
        JsonMapper objectMapper = new JsonMapper();
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        ConceptDiscoveryMcpDtos.ResolveInput directMapper = objectMapper.convertValue(
                mapperConceptResolveArguments(), ConceptDiscoveryMcpDtos.ResolveInput.class);
        assertThat(validator.validate(directMapper)).isEmpty();
        ConceptDiscoveryMcpDtos.ResolveInput mapper = decoder.decode(
                mapperConceptResolveArguments(), ConceptDiscoveryMcpDtos.ResolveInput.class);

        assertThat(new ConceptDiscoveryMcpMapper().toDomain(schedule.identity()))
                .isInstanceOf(ScheduleConceptIdentity.class);
        assertThat(new ConceptDiscoveryMcpMapper().toDomain(mapper.identity()))
                .isInstanceOf(MapperStatementVariantEvidenceIdentity.class);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nestedPositionCases")
    void should_preserve_missing_and_zero_nested_position_contract(
            String caseName,
            Map<String, Object> position,
            boolean accepted) {
        if (accepted) {
            SourceDiscoveryMcpDtos.InternalReferencesInput input = decoder.decode(
                    internalReferenceArguments(position), SourceDiscoveryMcpDtos.InternalReferencesInput.class);

            assertThat(input.target()).isInstanceOf(Member.class);
            Member target = (Member) input.target();
            assertThat(target.identity()).isInstanceOf(MethodScoped.class);
            MethodScoped identity = (MethodScoped) target.identity();
            assertThat(identity.declarationRange().start().line()).isZero();
            assertThat(identity.declarationRange().start().character()).isZero();
        } else {
            assertInvalidToolInput(() -> decoder.decode(
                    internalReferenceArguments(position), SourceDiscoveryMcpDtos.InternalReferencesInput.class));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sourceSymbolPositionCases")
    void should_preserve_source_symbol_position_contract(
            String caseName,
            Map<String, Object> position,
            boolean accepted) {
        if (accepted) {
            SourceDiscoveryMcpDtos.ResolveSymbolInput input = decoder.decode(
                    sourceSymbolArguments(position), SourceDiscoveryMcpDtos.ResolveSymbolInput.class);

            assertThat(input.position()).hasValue(new SyntaxPosition(0, 0));
        } else {
            assertInvalidToolInput(() -> decoder.decode(
                    sourceSymbolArguments(position), SourceDiscoveryMcpDtos.ResolveSymbolInput.class));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sourceSegmentLocationCases")
    void should_preserve_source_segment_location_contract(
            String caseName,
            Map<String, Object> start,
            Map<String, Object> end,
            boolean accepted) {
        if (accepted) {
            SourceDiscoveryMcpDtos.SourceSegmentInput input = decoder.decode(
                    sourceSegmentArguments(start, end), SourceDiscoveryMcpDtos.SourceSegmentInput.class);

            assertThat(input.location()).isEqualTo(new SourceRange(
                    "src/main/java/com/example/OrderService.java",
                    new SyntaxRange(new SyntaxPosition(0, 0), new SyntaxPosition(0, 0))));
        } else {
            assertInvalidToolInput(() -> decoder.decode(
                    sourceSegmentArguments(start, end), SourceDiscoveryMcpDtos.SourceSegmentInput.class));
        }
    }

    private static Map<String, Object> conceptResolveArguments() {
        return Map.of(
                "repoId", "orders",
                "expectedRevision", "FIXTURE",
                "identity", Map.of(
                        "kind", "API_ROUTE",
                        "target", methodTarget(),
                        "httpVerb", "GET",
                        "route", "/orders"));
    }

    private static Map<String, Object> internalReferenceArguments() {
        return internalReferenceArguments(Map.of("line", 10, "character", 4));
    }

    private static Map<String, Object> internalReferenceArguments(Map<String, Object> startPosition) {
        return Map.of(
                "repoId", "orders",
                "expectedRevision", "FIXTURE",
                "target", Map.of(
                        "kind", "MEMBER",
                        "identity", Map.of(
                                "scope", "METHOD",
                                "declaringMethod", methodTarget(),
                                "declarationRange", syntaxRange(startPosition),
                                "name", "request")),
                "offset", 0,
                "limit", 20);
    }

    private static Map<String, Object> evidenceSourceArguments() {
        return Map.of(
                "repoId", "orders",
                "expectedRevision", "FIXTURE",
                "identity", Map.of(
                        "kind", "MAPPER_FRAGMENT",
                        "identity", Map.of(
                                "namespace", "com.example.OrderMapper",
                                "fragmentId", "columns",
                                "resourcePath", "mapper/OrderMapper.xml",
                                "documentOrdinal", 0,
                                "representation", "MAPPER_XML_ELEMENT")));
    }

    private static Map<String, Object> sourceSymbolArguments(Map<String, Object> position) {
        return Map.of(
                "repoId", "orders",
                "expectedRevision", "FIXTURE",
                "context", Map.of(
                        "javaType", Map.of("packageName", "com.example", "className", "OrderService"),
                        "method", Map.of("name", "getOrder", "parameterTypes", List.of("java.lang.String"))),
                "symbol", "order",
                "position", position);
    }

    private static Map<String, Object> sourceSegmentArguments(
            Map<String, Object> start,
            Map<String, Object> end) {
        return Map.of(
                "repoId", "orders",
                "expectedRevision", "FIXTURE",
                "location", Map.of(
                        "sourceFile", "src/main/java/com/example/OrderService.java",
                        "range", Map.of("start", start, "end", end)),
                "contextLines", 0);
    }

    private static Map<String, Object> scheduleConceptResolveArguments() {
        return Map.of(
                "repoId", "orders",
                "expectedRevision", "FIXTURE",
                "identity", Map.of(
                        "kind", "SCHEDULE",
                        "target", methodTarget(),
                        "triggerKind", "CRON"));
    }

    private static Map<String, Object> mapperConceptResolveArguments() {
        return Map.of(
                "repoId", "orders",
                "expectedRevision", "FIXTURE",
                "identity", Map.of(
                        "kind", "MAPPER_STATEMENT_VARIANT",
                        "identity", Map.of(
                                "statementKey", Map.of("namespace", "com.example.OrderMapper", "statementId", "findOrder"),
                                "resourcePath", "mapper/OrderMapper.xml",
                                "documentOrdinal", 0,
                                "representation", "MAPPER_XML_ELEMENT")));
    }

    private static Map<String, Object> methodTarget() {
        return Map.of(
                "sourceType", Map.of(
                        "javaType", Map.of("packageName", "com.example", "className", "OrderController"),
                        "sourceFile", "src/main/java/com/example/OrderController.java"),
                "methodName", "getOrder",
                "parameterTypes", List.of("java.lang.String"));
    }

    private static Stream<Arguments> nestedPositionCases() {
        return Stream.of(
                Arguments.of("missing line", Map.of("character", 0), false),
                Arguments.of("missing character", Map.of("line", 0), false),
                Arguments.of("null line", positionWithNull("line"), false),
                Arguments.of("null character", positionWithNull("character"), false),
                Arguments.of("zero position", Map.of("line", 0, "character", 0), true));
    }

    private static Stream<Arguments> sourceSymbolPositionCases() {
        return Stream.of(
                Arguments.of("missing line", Map.of("character", 0), false),
                Arguments.of("missing character", Map.of("line", 0), false),
                Arguments.of("null line", positionWithNull("line"), false),
                Arguments.of("null character", positionWithNull("character"), false),
                Arguments.of("zero position", Map.of("line", 0, "character", 0), true));
    }

    private static Stream<Arguments> sourceSegmentLocationCases() {
        return Stream.of(
                Arguments.of("missing start character", Map.of("line", 0), Map.of("line", 0, "character", 0), false),
                Arguments.of("null end line", Map.of("line", 0, "character", 0), positionWithNull("line"), false),
                Arguments.of("zero location", Map.of("line", 0, "character", 0), Map.of("line", 0, "character", 0), true));
    }

    private static Map<String, Object> positionWithNull(String nullComponent) {
        Map<String, Object> position = new HashMap<>();
        position.put("line", 0);
        position.put("character", 0);
        position.put(nullComponent, null);
        return position;
    }

    private static Map<String, Object> syntaxRange(Map<String, Object> startPosition) {
        return Map.of(
                "start", startPosition,
                "end", Map.of("line", 10, "character", 11));
    }

    private static void assertInvalidToolInput(ThrowingCallable invocation) {
        assertThatThrownBy(invocation)
                .isInstanceOf(McpToolContractException.class)
                .satisfies(exception -> {
                    McpToolContractException contractException = (McpToolContractException) exception;
                    assertThat(contractException.code()).isEqualTo("INVALID_TOOL_INPUT");
                    assertThat(contractException.getMessage()).isEqualTo("INVALID_TOOL_INPUT");
                });
    }
}
