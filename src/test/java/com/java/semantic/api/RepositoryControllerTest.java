package com.java.semantic.api;

import com.java.semantic.api.dto.ApiErrorResponse;
import com.java.semantic.api.security.ApiTokenFilter;
import com.java.semantic.repository.application.ImmutableFixtureException;
import com.java.semantic.repository.application.RepositoryApplicationService;
import com.java.semantic.repository.application.RepositoryBusyException;
import com.java.semantic.repository.application.RepositoryMutationException;
import com.java.semantic.repository.application.RepositoryNotFoundException;
import com.java.semantic.repository.application.RepositoryNotReadyException;
import com.java.semantic.repository.application.RepositoryRevisionMismatchException;
import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryMode;
import com.java.semantic.repository.domain.RepositoryRevision;
import com.java.semantic.repository.domain.RepositoryStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "semantic.api.api-token=test-token",
        "semantic.git-token=ghp_realsecretvalue",
        "semantic.repositories.test-repo.url=https://user:ghp_realsecretvalue@example.com/repo.git"
})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class RepositoryControllerTest {

    private static final String TOKEN = "test-token";
    private static final String CONFIGURED_GIT_TOKEN = "ghp_realsecretvalue";
    private static final RepositoryId REPOSITORY_ID = RepositoryId.of("test-repo");
    private static final RepositoryStatus FIXTURE_STATUS = new RepositoryStatus(
            REPOSITORY_ID,
            RepositoryMode.LOCAL_FIXTURE,
            "test-repo",
            Optional.empty(),
            Optional.of(RepositoryRevision.fixture()),
            true);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RepositoryApplicationService repositoryApplicationService;

    @BeforeEach
    void setUp() {
        given(repositoryApplicationService.status(REPOSITORY_ID)).willReturn(FIXTURE_STATUS);
        given(repositoryApplicationService.list()).willReturn(List.of(FIXTURE_STATUS));
        given(repositoryApplicationService.status(RepositoryId.of("nope")))
                .willThrow(new RepositoryNotFoundException(RepositoryId.of("nope")));
        given(repositoryApplicationService.sync(eq(REPOSITORY_ID), any()))
                .willThrow(new ImmutableFixtureException());
    }

    @Test
    void should_return_status_without_any_filesystem_path_when_repository_is_fetched() throws Exception {
        ResultActions result = mockMvc.perform(get("/v1/repositories/test-repo")
                        .header(ApiTokenFilter.API_TOKEN_HEADER, TOKEN))
                .andExpect(status().isOk());

        assertSafeStatus(result, "$");
        then(repositoryApplicationService).should().status(REPOSITORY_ID);
    }

    @Test
    void should_return_safe_statuses_when_repositories_are_listed() throws Exception {
        ResultActions result = mockMvc.perform(get("/v1/repositories")
                        .header(ApiTokenFilter.API_TOKEN_HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        assertSafeStatus(result, "$[0]");
        then(repositoryApplicationService).should().list();
    }

    @Test
    void should_ensure_exact_repository_when_ensure_is_requested() throws Exception {
        given(repositoryApplicationService.ensure(REPOSITORY_ID)).willReturn(FIXTURE_STATUS);

        ResultActions result = mockMvc.perform(post("/v1/repositories/test-repo/ensure")
                        .header(ApiTokenFilter.API_TOKEN_HEADER, TOKEN))
                .andExpect(status().isOk());

        assertSafeStatus(result, "$");
        then(repositoryApplicationService).should().ensure(REPOSITORY_ID);
    }

    @Test
    void should_forward_branch_when_repository_sync_is_requested() throws Exception {
        Optional<String> branch = Optional.of("release");
        willReturn(FIXTURE_STATUS).given(repositoryApplicationService).sync(REPOSITORY_ID, branch);

        ResultActions result = mockMvc.perform(post("/v1/repositories/test-repo/sync")
                        .header(ApiTokenFilter.API_TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"branch":"release"}
                                """))
                .andExpect(status().isOk());

        assertSafeStatus(result, "$");
        then(repositoryApplicationService).should().sync(REPOSITORY_ID, branch);
    }

    @Test
    void should_normalize_branch_to_empty_when_repository_sync_branch_is_blank() throws Exception {
        Optional<String> branch = Optional.empty();
        willReturn(FIXTURE_STATUS).given(repositoryApplicationService).sync(REPOSITORY_ID, branch);

        ResultActions result = mockMvc.perform(post("/v1/repositories/test-repo/sync")
                        .header(ApiTokenFilter.API_TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"branch":"   "}
                                """))
                .andExpect(status().isOk());

        assertSafeStatus(result, "$");
        then(repositoryApplicationService).should().sync(REPOSITORY_ID, branch);
    }

    @Test
    void should_forward_revision_when_repository_checkout_is_requested() throws Exception {
        String revision = "release-tag";
        given(repositoryApplicationService.checkout(REPOSITORY_ID, revision)).willReturn(FIXTURE_STATUS);

        ResultActions result = mockMvc.perform(post("/v1/repositories/test-repo/checkout")
                        .header(ApiTokenFilter.API_TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"revision":"release-tag"}
                                """))
                .andExpect(status().isOk());

        assertSafeStatus(result, "$");
        then(repositoryApplicationService).should().checkout(REPOSITORY_ID, revision);
    }

    @Test
    void should_return_not_found_when_repo_id_is_not_configured() throws Exception {
        mockMvc.perform(get("/v1/repositories/nope")
                        .header(ApiTokenFilter.API_TOKEN_HEADER, TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("REPOSITORY_NOT_FOUND"));
    }

    @Test
    void should_return_bad_request_when_repo_id_would_escape_the_data_root() throws Exception {
        mockMvc.perform(get("/v1/repositories/..")
                        .header(ApiTokenFilter.API_TOKEN_HEADER, TOKEN))
                .andExpect(status().isBadRequest());
    }

    @Test
    void should_return_conflict_when_syncing_a_local_fixture() throws Exception {
        mockMvc.perform(post("/v1/repositories/test-repo/sync")
                        .header(ApiTokenFilter.API_TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("REPOSITORY_IMMUTABLE_FIXTURE"));
    }

    @Test
    void should_return_request_invalid_when_sync_request_body_is_absent() throws Exception {
        mockMvc.perform(post("/v1/repositories/test-repo/sync")
                        .header(ApiTokenFilter.API_TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_INVALID"))
                .andExpect(jsonPath("$.message").value("request body is invalid"));
    }

    @Test
    void should_return_request_invalid_when_checkout_request_json_is_malformed() throws Exception {
        mockMvc.perform(post("/v1/repositories/test-repo/checkout")
                        .header(ApiTokenFilter.API_TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_INVALID"))
                .andExpect(jsonPath("$.message").value("request body is invalid"));
    }

    @Test
    void should_return_request_invalid_when_checkout_revision_is_blank() throws Exception {
        mockMvc.perform(post("/v1/repositories/test-repo/checkout")
                        .header(ApiTokenFilter.API_TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"revision":"   "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_INVALID"))
                .andExpect(jsonPath("$.message").value("request body is invalid"));
    }

    @Test
    void should_return_conflict_when_repository_lock_times_out() throws Exception {
        given(repositoryApplicationService.status(REPOSITORY_ID))
                .willThrow(new RepositoryBusyException("internal lock detail"));

        mockMvc.perform(get("/v1/repositories/test-repo")
                        .header(ApiTokenFilter.API_TOKEN_HEADER, TOKEN))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("REPOSITORY_BUSY"))
                .andExpect(jsonPath("$.message").value("repository is busy"));
    }

    @Test
    void should_return_conflict_when_repository_has_no_snapshot() throws Exception {
        given(repositoryApplicationService.status(REPOSITORY_ID))
                .willThrow(new RepositoryNotReadyException(REPOSITORY_ID));

        mockMvc.perform(get("/v1/repositories/test-repo")
                        .header(ApiTokenFilter.API_TOKEN_HEADER, TOKEN))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("REPOSITORY_NOT_READY"));
    }

    @Test
    void should_reject_request_when_api_token_is_missing() throws Exception {
        mockMvc.perform(get("/v1/repositories")).andExpect(status().isUnauthorized());
    }

    @Test
    void should_allow_health_without_a_token_when_no_repositories_are_configured() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void should_not_leak_git_token_or_url_when_repository_list_is_returned() throws Exception {
        String body = mockMvc.perform(get("/v1/repositories")
                        .header(ApiTokenFilter.API_TOKEN_HEADER, TOKEN))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(CONFIGURED_GIT_TOKEN);
        assertThat(body).doesNotContain("http://").doesNotContain("https://");
    }

    @Test
    void should_sanitize_when_git_failure_contains_a_credential_bearing_url() throws Exception {
        given(repositoryApplicationService.ensure(REPOSITORY_ID))
                .willThrow(new RepositoryMutationException(
                        "clone failed: https://user:ghp_realsecretvalue@example.com/repo.git"));

        String body = mockMvc.perform(post("/v1/repositories/test-repo/ensure")
                        .header(ApiTokenFilter.API_TOKEN_HEADER, TOKEN))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errorCode").value("REPOSITORY_MUTATION_FAILED"))
                .andExpect(jsonPath("$.message").value("repository mutation failed"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(CONFIGURED_GIT_TOKEN);
        assertThat(body).doesNotContain("https://");
    }

    @Test
    void should_not_log_credentials_when_git_failure_contains_a_credential_bearing_url(
            CapturedOutput output) throws Exception {
        given(repositoryApplicationService.ensure(REPOSITORY_ID))
                .willThrow(new RepositoryMutationException(
                        "clone failed: https://user:ghp_realsecretvalue@example.com/repo.git"));
        int outputStart = output.getAll().length();

        mockMvc.perform(post("/v1/repositories/test-repo/ensure")
                        .header(ApiTokenFilter.API_TOKEN_HEADER, TOKEN))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errorCode").value("REPOSITORY_MUTATION_FAILED"))
                .andExpect(jsonPath("$.message").value("repository mutation failed"));

        String requestOutput = output.getAll().substring(outputStart);
        assertThat(requestOutput).doesNotContain(CONFIGURED_GIT_TOKEN);
        assertThat(requestOutput).doesNotContain("https://");
    }

    @Test
    void should_include_both_revisions_when_revision_mismatches() {
        RepositoryRevision expected = RepositoryRevision.ofSha("0".repeat(40));
        RepositoryRevision current = RepositoryRevision.ofSha("1".repeat(40));
        ApiExceptionHandler handler = new ApiExceptionHandler();

        ResponseEntity<ApiErrorResponse> response = handler.revisionMismatch(
                new RepositoryRevisionMismatchException(expected, current));
        ApiErrorResponse body = Objects.requireNonNull(response.getBody(), "response body is required");

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(body.currentRevision()).contains(current.value());
        assertThat(body.expectedRevision()).contains(expected.value());
    }

    private ResultActions assertSafeStatus(ResultActions result, String prefix) throws Exception {
        return result
                .andExpect(jsonPath(prefix + ".repoId").value("test-repo"))
                .andExpect(jsonPath(prefix + ".mode").value("LOCAL_FIXTURE"))
                .andExpect(jsonPath(prefix + ".displayName").value("test-repo"))
                .andExpect(jsonPath(prefix + ".currentBranch").doesNotExist())
                .andExpect(jsonPath(prefix + ".currentRevision").value("FIXTURE"))
                .andExpect(jsonPath(prefix + ".cloned").value(true))
                .andExpect(jsonPath(prefix + ".sourceRoot").doesNotExist())
                .andExpect(jsonPath(prefix + ".path").doesNotExist())
                .andExpect(jsonPath(prefix + ".url").doesNotExist());
    }
}
