package com.java.semantic.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import com.java.semantic.syntax.application.ExactContentApplicationService;
import com.java.semantic.syntax.application.ExactContentQuery;
import com.java.semantic.syntax.application.ExactContentResult;
import com.java.semantic.syntax.application.RevisionBoundEntryPoints;
import com.java.semantic.syntax.domain.ApiEntryPoint;
import com.java.semantic.syntax.domain.EntryPointClass;
import com.java.semantic.syntax.domain.EntryPointType;
import com.java.semantic.syntax.domain.MethodTargetResolution;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
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
    private ExactContentApplicationService exactContentApplicationService;

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
        given(exactContentApplicationService.retrieve(eq(new ExactContentQuery.MethodSource(
                REPOSITORY_ID,
                REVISION,
                TARGET))))
                .willReturn(exactContent());

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
                .andExpect(jsonPath("$.variants[0].content").value("return orderService.place(id);"));
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

    private static ExactContentResult exactContent() {
        String content = "return orderService.place(id);";
        ExactContentResult.Content inlineContent = new ExactContentResult.Content(
                Optional.of(content),
                Optional.empty(),
                content.getBytes(StandardCharsets.UTF_8).length,
                0);
        ExactContentResult.ContentVariant variant = new ExactContentResult.ContentVariant(
                Optional.empty(),
                Optional.empty(),
                inlineContent);
        return new ExactContentResult(REPOSITORY_ID, REVISION, List.of(variant));
    }
}
