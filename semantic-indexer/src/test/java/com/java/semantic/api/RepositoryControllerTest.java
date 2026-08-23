package com.java.semantic.api;

import com.java.semantic.api.security.ApiTokenFilter;
import com.java.semantic.indexer.job.IndexJobAlreadyActiveException;
import com.java.semantic.indexer.job.IndexRequestService;
import com.java.semantic.indexer.job.MongoIndexJobStore;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.repository.application.RepositoryApplicationService;
import com.java.semantic.repository.application.RepositoryNotFoundException;
import com.java.semantic.repository.domain.RepositoryMode;
import com.java.semantic.repository.domain.RepositoryStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "semantic.api.api-token=test-token",
        "semantic.api.admin-token=admin-token",
        "semantic.git-token=ghp_realsecretvalue",
        "semantic.repositories.test-repo.url=https://user:ghp_realsecretvalue@example.com/repo.git"
})
@AutoConfigureMockMvc
class RepositoryControllerTest {
    private static final String TOKEN = "test-token";
    private static final RepositoryId REPOSITORY_ID = RepositoryId.of("test-repo");
    private static final RepositoryStatus STATUS = new RepositoryStatus(
            REPOSITORY_ID, RepositoryMode.LOCAL_FIXTURE, "test-repo", Optional.empty(),
            Optional.of(new RepositoryRevision("0".repeat(40))), true);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RepositoryApplicationService repositories;

    @MockitoBean
    private IndexRequestService indexRequests;

    @MockitoBean
    private MongoIndexJobStore jobs;

    @BeforeEach
    void setUp() {
        given(repositories.list()).willReturn(List.of(STATUS));
        given(repositories.status(REPOSITORY_ID)).willReturn(STATUS);
        given(repositories.status(RepositoryId.of("missing")))
                .willThrow(new RepositoryNotFoundException(RepositoryId.of("missing")));
    }

    @Test
    void list_and_status_expose_only_safe_read_metadata() throws Exception {
        String list = mockMvc.perform(get("/v1/repositories").header(ApiTokenFilter.API_TOKEN_HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].repoId").value("test-repo"))
                .andReturn().getResponse().getContentAsString();
        String single = mockMvc.perform(get("/v1/repositories/test-repo").header(ApiTokenFilter.API_TOKEN_HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentRevision").value("0".repeat(40)))
                .andReturn().getResponse().getContentAsString();

        assertThat(list).doesNotContain("ghp_realsecretvalue", "https://", "sourceRoot", "path");
        assertThat(single).doesNotContain("ghp_realsecretvalue", "https://", "sourceRoot", "path");
    }

    @Test
    void read_routes_require_the_read_token_and_map_unknown_repositories_to_not_found() throws Exception {
        mockMvc.perform(get("/v1/repositories")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/v1/repositories/missing").header(ApiTokenFilter.API_TOKEN_HEADER, TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("REPOSITORY_NOT_FOUND"));
    }

    @Test
    void index_mutation_maps_an_active_job_to_a_token_safe_conflict() throws Exception {
        given(indexRequests.ensure(REPOSITORY_ID)).willThrow(new IndexJobAlreadyActiveException(REPOSITORY_ID));

        mockMvc.perform(post("/index/repositories/test-repo/ensure")
                        .header(ApiTokenFilter.API_TOKEN_HEADER, "admin-token"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INDEX_JOB_ALREADY_ACTIVE"))
                .andExpect(jsonPath("$.message").value("an index job is already active"));
    }
}
