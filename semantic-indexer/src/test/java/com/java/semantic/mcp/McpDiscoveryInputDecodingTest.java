package com.java.semantic.mcp;

import com.java.semantic.mcp.dto.concept.ConceptDiscoveryMcpDtos;
import com.java.semantic.mcp.dto.source.SourceDiscoveryMcpDtos;
import com.java.semantic.mcp.mapper.ConceptDiscoveryMcpMapper;
import com.java.semantic.mcp.mapper.SourceDiscoveryMcpMapper;
import com.java.semantic.syntax.application.EvidenceSourceQuery;
import com.java.semantic.syntax.application.concept.EntryPointConceptIdentity.ApiRouteConceptIdentity;
import com.java.semantic.syntax.application.concept.EntryPointConceptIdentity.ScheduleConceptIdentity;
import com.java.semantic.syntax.application.concept.MapperConceptIdentity.MapperStatementVariantEvidenceIdentity;
import com.java.semantic.syntax.domain.ExactSourceDeclarationTarget;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

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

    private static Map<String, Object> conceptResolveArguments() {
        return Map.of(
                "repoId", "orders",
                "expectedRevision", "0000000000000000000000000000000000000000",
                "identity", Map.of(
                        "kind", "API_ROUTE",
                        "target", methodTarget(),
                        "httpVerb", "GET",
                        "route", "/orders"));
    }

    private static Map<String, Object> internalReferenceArguments() {
        return Map.of(
                "repoId", "orders",
                "expectedRevision", "0000000000000000000000000000000000000000",
                "target", Map.of(
                        "kind", "MEMBER",
                        "identity", Map.of(
                                "scope", "METHOD",
                                "declaringMethod", methodTarget(),
                                "declarationRange", syntaxRange(),
                                "name", "request")),
                "offset", 0,
                "limit", 20);
    }

    private static Map<String, Object> evidenceSourceArguments() {
        return Map.of(
                "repoId", "orders",
                "expectedRevision", "0000000000000000000000000000000000000000",
                "identity", Map.of(
                        "kind", "MAPPER_FRAGMENT",
                        "identity", Map.of(
                                "namespace", "com.example.OrderMapper",
                                "fragmentId", "columns",
                                "resourcePath", "mapper/OrderMapper.xml",
                                "documentOrdinal", 0,
                                "representation", "MAPPER_XML_ELEMENT")));
    }

    private static Map<String, Object> scheduleConceptResolveArguments() {
        return Map.of(
                "repoId", "orders",
                "expectedRevision", "0000000000000000000000000000000000000000",
                "identity", Map.of(
                        "kind", "SCHEDULE",
                        "target", methodTarget(),
                        "triggerKind", "CRON"));
    }

    private static Map<String, Object> mapperConceptResolveArguments() {
        return Map.of(
                "repoId", "orders",
                "expectedRevision", "0000000000000000000000000000000000000000",
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

    private static Map<String, Object> syntaxRange() {
        return Map.of(
                "start", Map.of("line", 10, "character", 4),
                "end", Map.of("line", 10, "character", 11));
    }
}
