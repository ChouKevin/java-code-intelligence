package com.java.semantic.api;

import com.java.semantic.query.application.CodeFactReadService;
import com.java.semantic.query.application.CodeFactSearchService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CodeFactControllerContractTest {

    @Test
    void exposes_a_flat_code_fact_http_surface() {
        assertDoesNotThrow(() -> Class.forName("com.java.semantic.api.CodeFactController"));
        assertDoesNotThrow(() -> Class.forName("com.java.semantic.api.dto.SearchCodeFactsRequest"));
        assertDoesNotThrow(() -> Class.forName("com.java.semantic.api.dto.GetCodeFactRequest"));
    }

    @Test
    void malformed_or_invalid_flat_search_requests_return_the_safe_request_invalid_envelope() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new CodeFactController(mock(CodeFactSearchService.class),
                mock(CodeFactReadService.class))).setControllerAdvice(new QueryApiExceptionHandler()).build();

        mockMvc.perform(post("/v1/code-facts/search").contentType(MediaType.APPLICATION_JSON).content("{\"repositoryId\":"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code", is("REQUEST_INVALID")))
                .andExpect(jsonPath("$.retryable", is(false))).andExpect(jsonPath("$.message").doesNotExist());
        mockMvc.perform(post("/v1/code-facts/search").contentType(MediaType.APPLICATION_JSON).content("{\"revision\":\"a\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code", is("REQUEST_INVALID")))
                .andExpect(jsonPath("$.retryable", is(false))).andExpect(jsonPath("$.message").doesNotExist());
    }
}
