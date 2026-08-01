package com.java.semantic.api;

import com.java.semantic.api.security.ApiTokenFilter;
import com.java.semantic.identity.JavaTypeIdentity;
import com.java.semantic.identity.MethodTarget;
import com.java.semantic.identity.SourceTypeIdentity;
import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;
import com.java.semantic.syntax.application.EntryPointDiscoveryApplicationService;
import com.java.semantic.syntax.application.RevisionBoundEntryPoints;
import com.java.semantic.syntax.domain.ApiEntryPoint;
import com.java.semantic.syntax.domain.EntryPointClass;
import com.java.semantic.syntax.domain.EntryPointType;
import com.java.semantic.syntax.domain.MqBroker;
import com.java.semantic.syntax.domain.MqEntryPoint;
import com.java.semantic.syntax.domain.MethodTargetResolution;
import com.java.semantic.syntax.domain.ScheduleEntryPoint;
import com.java.semantic.syntax.domain.ScheduleTriggerKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "semantic.api.api-token=test-token",
        "semantic.repositories.orders.url=https://example.invalid/orders.git"
})
@AutoConfigureMockMvc
class EntryPointControllerTest {

    private static final String TOKEN = "test-token";
    private static final RepositoryId REPOSITORY_ID = RepositoryId.of("orders");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EntryPointDiscoveryApplicationService entryPointDiscoveryApplicationService;

    @Test
    void should_default_omitted_types_to_all_entry_point_types() throws Exception {
        given(entryPointDiscoveryApplicationService.list(
                eq(REPOSITORY_ID), eq(EnumSet.allOf(EntryPointType.class))))
                .willReturn(resultWithAllMethods());

        mockMvc.perform(get("/v1/repositories/orders/entry-points")
                        .header(ApiTokenFilter.API_TOKEN_HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entryPoints[0].methods.length()").value(3));
        then(entryPointDiscoveryApplicationService).should().list(
                REPOSITORY_ID, EnumSet.allOf(EntryPointType.class));
    }

    @Test
    void should_parse_all_comma_separated_types_in_source_order() throws Exception {
        given(entryPointDiscoveryApplicationService.list(
                eq(REPOSITORY_ID), eq(EnumSet.of(
                        EntryPointType.API, EntryPointType.MQ, EntryPointType.SCHEDULE))))
                .willReturn(resultWithAllMethods());

        mockMvc.perform(get("/v1/repositories/orders/entry-points")
                        .queryParam("types", "API,MQ,SCHEDULE")
                        .header(ApiTokenFilter.API_TOKEN_HEADER, TOKEN))
                .andExpect(status().isOk());
        then(entryPointDiscoveryApplicationService).should().list(
                REPOSITORY_ID, EnumSet.of(
                        EntryPointType.API, EntryPointType.MQ, EntryPointType.SCHEDULE));
    }

    @Test
    void should_deduplicate_repeated_types() throws Exception {
        given(entryPointDiscoveryApplicationService.list(
                eq(REPOSITORY_ID), eq(EnumSet.of(EntryPointType.API, EntryPointType.MQ))))
                .willReturn(resultWithAllMethods());

        mockMvc.perform(get("/v1/repositories/orders/entry-points")
                        .queryParam("types", "API,MQ,API")
                        .header(ApiTokenFilter.API_TOKEN_HEADER, TOKEN))
                .andExpect(status().isOk());
        then(entryPointDiscoveryApplicationService).should().list(
                REPOSITORY_ID, EnumSet.of(EntryPointType.API, EntryPointType.MQ));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "API,", "API, MQ", "UNKNOWN"})
    void should_reject_blank_types_or_unknown_or_whitespace_padded_type(String types) throws Exception {
        mockMvc.perform(get("/v1/repositories/orders/entry-points")
                        .queryParam("types", types)
                        .header(ApiTokenFilter.API_TOKEN_HEADER, TOKEN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_INVALID"));
        then(entryPointDiscoveryApplicationService).shouldHaveNoInteractions();
    }

    @Test
    void should_return_exact_revision_and_all_method_variants() throws Exception {
        given(entryPointDiscoveryApplicationService.list(
                eq(REPOSITORY_ID), eq(EnumSet.allOf(EntryPointType.class))))
                .willReturn(resultWithAllMethods());

        mockMvc.perform(get("/v1/repositories/orders/entry-points")
                        .queryParam("types", "API,MQ,SCHEDULE")
                        .header(ApiTokenFilter.API_TOKEN_HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repoId").value("orders"))
                .andExpect(jsonPath("$.analyzedRevision").value("FIXTURE"))
                .andExpect(jsonPath("$.entryPoints[0].packagePath")
                        .value("com/acme/OrderController.java"))
                .andExpect(jsonPath("$.entryPoints[0].methods[0].type").value("API"))
                .andExpect(jsonPath("$.entryPoints[0].methods[0].apiUrl").value("/orders"))
                .andExpect(jsonPath("$.entryPoints[0].methods[1].type").value("MQ"))
                .andExpect(jsonPath("$.entryPoints[0].methods[1].broker").value("KAFKA"))
                .andExpect(jsonPath("$.entryPoints[0].methods[2].type").value("SCHEDULE"))
                .andExpect(jsonPath("$.entryPoints[0].methods[2].triggerKind").value("CRON"))
                .andExpect(jsonPath("$.entryPoints[0].sourceRoot").doesNotExist())
                .andExpect(jsonPath("$.entryPoints[0].path").doesNotExist());
    }

    @Test
    void should_return_exact_fixture_revision_and_empty_list_for_repository_policy_denial() throws Exception {
        given(entryPointDiscoveryApplicationService.list(
                eq(REPOSITORY_ID), eq(EnumSet.allOf(EntryPointType.class))))
                .willReturn(new RevisionBoundEntryPoints(
                        REPOSITORY_ID, RepositoryRevision.fixture(), List.of()));

        mockMvc.perform(get("/v1/repositories/orders/entry-points")
                        .header(ApiTokenFilter.API_TOKEN_HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repoId").value("orders"))
                .andExpect(jsonPath("$.analyzedRevision").value("FIXTURE"))
                .andExpect(jsonPath("$.entryPoints").isEmpty());
    }

    @Test
    void should_expose_only_relative_package_path_and_approved_fields() throws Exception {
        given(entryPointDiscoveryApplicationService.list(
                eq(REPOSITORY_ID), eq(EnumSet.allOf(EntryPointType.class))))
                .willReturn(resultWithAllMethods());

        String body = mockMvc.perform(get("/v1/repositories/orders/entry-points")
                        .header(ApiTokenFilter.API_TOKEN_HEADER, TOKEN))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("com/acme/OrderController.java")
                .doesNotContain("sourceRoot")
                .doesNotContain("/workspace")
                .doesNotContain("file:")
                .doesNotContain("uri")
                .doesNotContain("source text")
                .doesNotContain("annotations")
                .doesNotContain("sql")
                .doesNotContain("policy");
    }

    @Test
    void should_reject_missing_entry_point_token() throws Exception {
        mockMvc.perform(get("/v1/repositories/orders/entry-points"))
                .andExpect(status().isUnauthorized());
        then(entryPointDiscoveryApplicationService).shouldHaveNoInteractions();
    }

    @Test
    void should_reject_invalid_entry_point_token() throws Exception {
        mockMvc.perform(get("/v1/repositories/orders/entry-points")
                        .header(ApiTokenFilter.API_TOKEN_HEADER, "wrong"))
                .andExpect(status().isUnauthorized());
        then(entryPointDiscoveryApplicationService).shouldHaveNoInteractions();
    }

    @Test
    void should_serialize_resolved_and_unresolved_entry_point_targets() throws Exception {
        given(entryPointDiscoveryApplicationService.list(
                eq(REPOSITORY_ID), eq(EnumSet.allOf(EntryPointType.class))))
                .willReturn(resultWithAllMethods());

        mockMvc.perform(get("/v1/repositories/orders/entry-points")
                        .header(ApiTokenFilter.API_TOKEN_HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entryPoints[0].methods[0].analysisTarget.status").value("RESOLVED"))
                .andExpect(jsonPath("$.entryPoints[0].methods[0].analysisTarget.target.sourceFile")
                        .value("src/main/java/com/acme/OrderController.java"))
                .andExpect(jsonPath("$.entryPoints[0].methods[0].analysisTarget.target.parameterTypes[0]")
                        .value("java.lang.Long"))
                .andExpect(jsonPath("$.entryPoints[0].methods[1].analysisTarget.status").value("UNRESOLVED"))
                .andExpect(jsonPath("$.entryPoints[0].methods[1].analysisTarget.target").doesNotExist())
                .andExpect(jsonPath("$.entryPoints[0].methods[1].analysisTarget.candidates").isEmpty())
                .andExpect(jsonPath("$.entryPoints[0].methods[1].analysisTarget.reasonCode")
                        .value("METHOD_PARAMETER_BINDING_UNRESOLVED"));
    }

    private static RevisionBoundEntryPoints resultWithAllMethods() {
        EntryPointClass entryPointClass = new EntryPointClass(
                "OrderController",
                "com.acme",
                "com/acme/OrderController.java",
                "orders",
                List.of("/orders"),
                List.of(
                        new ApiEntryPoint("place", "place order", "/orders", List.of("GET"), List.of("orders"),
                                MethodTargetResolution.resolved(new MethodTarget(
                                        new SourceTypeIdentity(
                                                new JavaTypeIdentity("com.acme", "OrderController"),
                                                "src/main/java/com/acme/OrderController.java"),
                                        "place",
                                        List.of("java.lang.Long")))),
                        new MqEntryPoint("consume", "consume order", MqBroker.KAFKA, List.of("orders"),
                                MethodTargetResolution.unresolved("METHOD_PARAMETER_BINDING_UNRESOLVED")),
                        new ScheduleEntryPoint("refresh", "refresh orders", ScheduleTriggerKind.CRON, "0 * * * *",
                                MethodTargetResolution.unresolved("METHOD_PARAMETER_BINDING_UNRESOLVED"))));
        return new RevisionBoundEntryPoints(
                REPOSITORY_ID, RepositoryRevision.fixture(), List.of(entryPointClass));
    }
}
