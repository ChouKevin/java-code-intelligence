package com.java.semantic.api;

import com.java.semantic.api.security.ApiTokenFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "semantic.api.api-token=test-token")
@AutoConfigureMockMvc
class SemanticDocumentationTest {

    private static final String API_TOKEN = "test-token";
    private static final YAMLMapper YAML_MAPPER = new YAMLMapper();

    @Autowired
    private MockMvc mockMvc;

    @Test
    void should_serve_the_authoritative_openapi_contract_and_public_swagger_boundary() throws Exception {
        MvcResult yamlResult = mockMvc.perform(get("/openapi/semantic-api-v1.yaml"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode document = YAML_MAPPER.readTree(yamlResult.getResponse().getContentAsByteArray());

        assertThat(document.path("openapi").asText()).isEqualTo("3.0.3");
        mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/swagger-ui/index.html"));
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/v3/api-docs/swagger-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("/openapi/semantic-api-v1.yaml"));
        mockMvc.perform(get("/v1/repositories"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/v3/api-docs").header(ApiTokenFilter.API_TOKEN_HEADER, API_TOKEN))
                .andExpect(status().isNotFound());
    }
}
