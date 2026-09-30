package com.java.semantic.indexer.api;

import com.java.semantic.indexer.application.IndexerPreparationFacade;
import com.java.semantic.indexer.job.IndexRequestService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class IndexRepositoryControllerTest {
    private static final String REQUEST_ID = "8f899830-47bb-4dc7-a9a6-c4ad0c016bb3";
    private static MockMvc mvc() {
        IndexRequestService service = mock(IndexRequestService.class);
        return MockMvcBuilders.standaloneSetup(new IndexRepositoryController(service, new IndexerPreparationFacade(service)))
                .setControllerAdvice(new IndexerApiExceptionHandler()).build();
    }
    @Test
    void review_admission_requires_a_client_generated_request_id() throws Exception {
        mvc().perform(post("/index/repositories/orders/reviews").contentType(MediaType.APPLICATION_JSON)
                .content("{\"selection\":{\"kind\":\"COMMIT\",\"revision\":\"" + "b".repeat(40) + "\"}}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }
    @Test
    void public_preparation_rejects_unknown_fields_wrong_types_and_conflicting_unions() throws Exception {
        MockMvc mvc = mvc();
        for (String body : List.of("{}", "{\"requestId\":null}", "{\"requestId\":42}",
                "{\"requestId\":\"" + REQUEST_ID.toUpperCase() + "\"}",
                "{\"requestId\":\"" + REQUEST_ID + "\",\"branch\":\"main\"}")) {
            mvc.perform(post("/index/repositories/orders/codebase").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        }
        for (String selection : List.of("null", "{}", "{\"kind\":\"COMMIT\",\"revision\":\"bad\"}",
                "{\"kind\":\"COMMIT\",\"revision\":\"" + "a".repeat(40) + "\",\"beforeRevision\":\"" + "a".repeat(40) + "\"}")) {
            mvc.perform(post("/index/repositories/orders/reviews").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"requestId\":\"" + REQUEST_ID + "\",\"selection\":" + selection + "}"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        }
    }
    @Test
    void job_lookup_requires_one_exact_selector_and_removed_routes_are_not_aliases() throws Exception {
        MockMvc mvc = mvc();
        for (String query : List.of("", "?jobId=" + REQUEST_ID + "&requestId=" + REQUEST_ID,
                "?requestId=" + REQUEST_ID + "&requestId=" + REQUEST_ID, "?latest=true")) {
            mvc.perform(get("/index/repositories/orders/jobs" + query)).andExpect(status().isBadRequest());
        }
        mvc.perform(get("/index/repositories/orders/jobs/" + REQUEST_ID)).andExpect(status().isNotFound());
        for (String route : List.of("refs", "history", "comparisons")) {
            mvc.perform(post("/index/repositories/orders/git/" + route).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isNotFound());
        }
    }
}
