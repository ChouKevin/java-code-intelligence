package com.java.semantic.api;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.java.semantic.api.security.ApiTokenFilter;
import com.java.semantic.identity.JavaTypeIdentity;
import com.java.semantic.identity.MethodTarget;
import com.java.semantic.identity.SourceTypeIdentity;
import com.java.semantic.repository.application.RepositoryApplicationService;
import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryMode;
import com.java.semantic.repository.domain.RepositoryRevision;
import com.java.semantic.repository.domain.RepositoryStatus;
import com.java.semantic.syntax.application.EntryPointDiscoveryApplicationService;
import com.java.semantic.syntax.application.MethodSourceApplicationService;
import com.java.semantic.syntax.application.MethodSourceQuery;
import com.java.semantic.syntax.application.MethodSourceResult;
import com.java.semantic.syntax.application.RevisionBoundEntryPoints;
import com.java.semantic.syntax.domain.ApiEntryPoint;
import com.java.semantic.syntax.domain.EntryPointClass;
import com.java.semantic.syntax.domain.EntryPointType;
import com.java.semantic.syntax.domain.MethodTargetResolution;
import com.java.semantic.syntax.domain.SourceRange;
import com.java.semantic.syntax.domain.SourceRangeSegment;
import com.java.semantic.syntax.domain.SyntaxPosition;
import com.java.semantic.syntax.domain.SyntaxRange;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 儲存庫狀態至 exact method source 的 typed navigation 契約測試 */
@SpringBootTest(properties = {
        "semantic.api.api-token=test-token",
        "semantic.repositories.orders.url=https://example.invalid/orders.git"
})
@AutoConfigureMockMvc
class SemanticNavigationContractTest {

    private static final String TOKEN = "test-token";
    private static final RepositoryId REPOSITORY_ID = RepositoryId.of("orders");
    private static final RepositoryRevision REVISION = RepositoryRevision.ofSha("1".repeat(40));
    private static final SourceTypeIdentity SOURCE_TYPE = new SourceTypeIdentity(
            new JavaTypeIdentity("com.acme", "Outer.InnerController"),
            "module-a/src/main/java/com/acme/Outer.java");
    private static final MethodTarget TARGET = new MethodTarget(
            SOURCE_TYPE,
            "place",
            List.of("java.lang.Long"));

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private RepositoryApplicationService repositoryApplicationService;

    @MockitoBean
    private EntryPointDiscoveryApplicationService entryPointDiscoveryApplicationService;

    @MockitoBean
    private MethodSourceApplicationService methodSourceApplicationService;

    @Test
    void should_compose_status_revision_and_resolved_target_into_exact_method_source_request() throws Exception {
        given(repositoryApplicationService.status(REPOSITORY_ID)).willReturn(new RepositoryStatus(
                REPOSITORY_ID,
                RepositoryMode.REMOTE,
                "Orders",
                Optional.of("main"),
                Optional.of(REVISION),
                true));
        given(entryPointDiscoveryApplicationService.list(
                REPOSITORY_ID,
                REVISION,
                EnumSet.allOf(EntryPointType.class)))
                .willReturn(entryPoints());
        given(methodSourceApplicationService.read(eq(new MethodSourceQuery(
                REPOSITORY_ID,
                REVISION,
                TARGET))))
                .willReturn(methodSource());

        MvcResult statusResult = mockMvc.perform(get("/v1/repositories/orders")
                        .header(ApiTokenFilter.API_TOKEN_HEADER, TOKEN))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode statusPayload = objectMapper.readTree(statusResult.getResponse().getContentAsString());
        String currentRevision = statusPayload.required("currentRevision").textValue();

        MvcResult entryPointResult = mockMvc.perform(get("/v1/repositories/orders/entry-points")
                        .queryParam("expectedRevision", currentRevision)
                        .header(ApiTokenFilter.API_TOKEN_HEADER, TOKEN))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode entryPointPayload = objectMapper.readTree(entryPointResult.getResponse().getContentAsString());
        JsonNode resolvedTarget = entryPointPayload.at("/entryPoints/0/methods/0/analysisTarget/target");
        assertThat(resolvedTarget.isMissingNode()).isFalse();

        ObjectNode exactRequest = objectMapper.createObjectNode();
        exactRequest.put("repoId", REPOSITORY_ID.value());
        exactRequest.put("expectedRevision", currentRevision);
        exactRequest.set("target", resolvedTarget);

        mockMvc.perform(post("/v1/discovery/method-source")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(exactRequest))
                        .header(ApiTokenFilter.API_TOKEN_HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repoId").value(REPOSITORY_ID.value()))
                .andExpect(jsonPath("$.analyzedRevision").value(currentRevision))
                .andExpect(jsonPath("$.declarationLocation.sourceFile").value(SOURCE_TYPE.sourceFile()))
                .andExpect(jsonPath("$.segment.content").value("return orderService.place(id);"))
                .andExpect(jsonPath("$.availableFollowUps.length()").value(3))
                .andExpect(jsonPath("$.availableFollowUps[0].operation").value("GET_METHOD_SOURCE"))
                .andExpect(jsonPath("$.availableFollowUps[1].operation")
                        .value("ANALYZE_OUTGOING_CALL_GRAPH"))
                .andExpect(jsonPath("$.availableFollowUps[2].operation")
                        .value("ANALYZE_INCOMING_CALL_GRAPH"))
                .andExpect(jsonPath("$.availableFollowUps[3]").doesNotExist());
    }

    @Test
    void should_append_exact_java_source_continuation_after_complete_method_navigation() throws Exception {
        SourceRange nextLocation = new SourceRange(
                SOURCE_TYPE.sourceFile(),
                new SyntaxRange(new SyntaxPosition(4, 0), new SyntaxPosition(8, 1)));
        given(methodSourceApplicationService.read(eq(new MethodSourceQuery(
                REPOSITORY_ID,
                REVISION,
                TARGET))))
                .willReturn(methodSource(Optional.of(nextLocation)));
        ObjectNode request = objectMapper.createObjectNode();
        request.put("repoId", REPOSITORY_ID.value());
        request.put("expectedRevision", REVISION.value());
        request.set("target", objectMapper.valueToTree(TARGET));

        mockMvc.perform(post("/v1/discovery/method-source")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request))
                        .header(ApiTokenFilter.API_TOKEN_HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableFollowUps.length()").value(4))
                .andExpect(jsonPath("$.availableFollowUps[0].operation").value("GET_METHOD_SOURCE"))
                .andExpect(jsonPath("$.availableFollowUps[1].operation")
                        .value("ANALYZE_OUTGOING_CALL_GRAPH"))
                .andExpect(jsonPath("$.availableFollowUps[2].operation")
                        .value("ANALYZE_INCOMING_CALL_GRAPH"))
                .andExpect(jsonPath("$.availableFollowUps[3].operation")
                        .value("GET_SOURCE_SEGMENT"))
                .andExpect(jsonPath("$.availableFollowUps[3].api.method").value("POST"))
                .andExpect(jsonPath("$.availableFollowUps[3].api.path")
                        .value("/v1/discovery/source-segment"))
                .andExpect(jsonPath("$.availableFollowUps[3].request.repoId")
                        .value(REPOSITORY_ID.value()))
                .andExpect(jsonPath("$.availableFollowUps[3].request.expectedRevision")
                        .value(REVISION.value()))
                .andExpect(jsonPath("$.availableFollowUps[3].request.location.sourceFile")
                        .value(SOURCE_TYPE.sourceFile()))
                .andExpect(jsonPath("$.availableFollowUps[3].request.location.range.start.line").value(4))
                .andExpect(jsonPath("$.availableFollowUps[3].request.location.range.start.character").value(0))
                .andExpect(jsonPath("$.availableFollowUps[3].request.location.range.end.line").value(8))
                .andExpect(jsonPath("$.availableFollowUps[3].request.location.range.end.character").value(1))
                .andExpect(jsonPath("$.availableFollowUps[3].request.contextLines").value(0));
    }

    @Test
    void should_offer_implementation_discovery_for_an_eligible_method_source_declaration() throws Exception {
        given(methodSourceApplicationService.read(eq(new MethodSourceQuery(
                REPOSITORY_ID,
                REVISION,
                TARGET))))
                .willReturn(methodSource(Optional.empty(), true));
        ObjectNode request = objectMapper.createObjectNode();
        request.put("repoId", REPOSITORY_ID.value());
        request.put("expectedRevision", REVISION.value());
        request.set("target", objectMapper.valueToTree(TARGET));

        mockMvc.perform(post("/v1/discovery/method-source")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request))
                        .header(ApiTokenFilter.API_TOKEN_HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableFollowUps.length()").value(4))
                .andExpect(jsonPath("$.availableFollowUps[3].operation")
                        .value("DISCOVER_METHOD_IMPLEMENTATIONS"));
    }

    private static RevisionBoundEntryPoints entryPoints() {
        ApiEntryPoint entryPoint = new ApiEntryPoint(
                "place",
                "place order",
                "/orders/{id}",
                List.of("POST"),
                List.of("orders"),
                MethodTargetResolution.resolved(TARGET));
        EntryPointClass entryPointClass = new EntryPointClass(
                SOURCE_TYPE,
                "orders",
                List.of("/orders"),
                List.of(entryPoint));
        return new RevisionBoundEntryPoints(REPOSITORY_ID, REVISION, List.of(entryPointClass));
    }

    private static MethodSourceResult methodSource() {
        return methodSource(Optional.empty());
    }

    private static MethodSourceResult methodSource(Optional<SourceRange> nextLocation) {
        return methodSource(nextLocation, false);
    }

    private static MethodSourceResult methodSource(
            Optional<SourceRange> nextLocation,
            boolean implementationDiscoveryEligible) {
        String content = "return orderService.place(id);";
        SourceRange location = new SourceRange(
                SOURCE_TYPE.sourceFile(),
                new SyntaxRange(new SyntaxPosition(3, 4), new SyntaxPosition(3, 35)));
        return new MethodSourceResult(
                REPOSITORY_ID,
                REVISION,
                location,
                new SourceRangeSegment(location, content, nextLocation, false), implementationDiscoveryEligible);
    }
}
