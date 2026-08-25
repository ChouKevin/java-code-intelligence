package com.java.semantic.indexer.api;

import com.java.semantic.indexer.job.IndexJob;
import com.java.semantic.indexer.job.IndexJobId;
import com.java.semantic.indexer.job.IndexJobPhase;
import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class IndexAdminSecurityTest {
    @Test
    void index_paths_require_the_separate_admin_token() throws Exception {
        IndexerAdminSecurityProperties properties = new IndexerAdminSecurityProperties();
        properties.setAdminToken("admin-token");
        IndexerAdminTokenFilter filter = new IndexerAdminTokenFilter(properties);

        String[] paths = {"/index/repositories/orders/ensure", "/index/repositories/orders/sync",
                "/index/repositories/orders/checkout", "/index/repositories/orders/rebuild",
                "/index/repositories/orders/rollback"};
        for (String path : paths) {
            MockHttpServletRequest absentTokenRequest = new MockHttpServletRequest(methodFor(path), path);
            MockHttpServletResponse absentTokenResponse = new MockHttpServletResponse();
            filter.doFilter(absentTokenRequest, absentTokenResponse, (request, response) ->
                    ((jakarta.servlet.http.HttpServletResponse) response).setStatus(204));
            assertThat(absentTokenResponse.getStatus()).isEqualTo(401);

            MockHttpServletRequest readTokenRequest = new MockHttpServletRequest(methodFor(path), path);
            readTokenRequest.addHeader(IndexerAdminTokenFilter.TOKEN_HEADER, "wrong-token");
            MockHttpServletResponse readTokenResponse = new MockHttpServletResponse();
            filter.doFilter(readTokenRequest, readTokenResponse, (request, response) -> ((jakarta.servlet.http.HttpServletResponse) response).setStatus(204));
            assertThat(readTokenResponse.getStatus()).isEqualTo(401);
            assertThat(readTokenResponse.getContentAsString()).doesNotContain("admin-token");

            MockHttpServletRequest adminTokenRequest = new MockHttpServletRequest(methodFor(path), path);
            adminTokenRequest.addHeader(IndexerAdminTokenFilter.TOKEN_HEADER, "admin-token");
            MockHttpServletResponse adminTokenResponse = new MockHttpServletResponse();
            filter.doFilter(adminTokenRequest, adminTokenResponse, (request, response) -> ((jakarta.servlet.http.HttpServletResponse) response).setStatus(204));
            assertThat(adminTokenResponse.getStatus()).isEqualTo(204);
        }
    }

    @Test
    void admin_token_is_redacted_from_properties_responses_and_job_representation() throws Exception {
        IndexerAdminSecurityProperties properties = new IndexerAdminSecurityProperties();
        properties.setAdminToken("admin-token");
        IndexerAdminTokenFilter filter = new IndexerAdminTokenFilter(properties);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/index/repositories/orders/ensure");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (servletRequest, servletResponse) ->
                ((jakarta.servlet.http.HttpServletResponse) servletResponse).setStatus(204));
        IndexJob job = new IndexJob(IndexJobId.create(), RepositoryId.of("orders"),
                new RepositoryRevision("a".repeat(40)), new GenerationId("g-orders"), 1,
                IndexJobPhase.ACCEPTED, true, java.util.Optional.empty(), java.util.Optional.empty(),
                java.util.Optional.empty(), java.util.Optional.empty());

        assertThat(response.getContentAsString()).doesNotContain("admin-token");
        assertThat(properties.toString()).doesNotContain("admin-token");
        assertThat(new tools.jackson.databind.ObjectMapper().writeValueAsString(job))
                .doesNotContain("admin-token", "read-token");
    }

    private static String methodFor(String path) {
        return "POST";
    }

    @Test
    void fails_closed_when_admin_auth_is_unset() throws Exception {
        IndexerAdminSecurityProperties properties = new IndexerAdminSecurityProperties();
        IndexerAdminTokenFilter filter = new IndexerAdminTokenFilter(properties);
        MockHttpServletRequest adminRequest = new MockHttpServletRequest("POST", "/index/repositories/orders/ensure");
        adminRequest.addHeader(IndexerAdminTokenFilter.TOKEN_HEADER, "wrong-token");
        MockHttpServletResponse adminResponse = new MockHttpServletResponse();
        filter.doFilter(adminRequest, adminResponse, (request, response) -> ((jakarta.servlet.http.HttpServletResponse) response).setStatus(204));

        assertThat(adminResponse.getStatus()).isEqualTo(403);
    }

    @Test
    void index_paths_remain_protected_below_a_servlet_context_path() throws Exception {
        IndexerAdminSecurityProperties properties = new IndexerAdminSecurityProperties();
        properties.setAdminToken("admin-token");
        IndexerAdminTokenFilter filter = new IndexerAdminTokenFilter(properties);
        MockHttpServletRequest absentToken = new MockHttpServletRequest("POST", "/semantic/index/repositories/orders/ensure");
        absentToken.setContextPath("/semantic");
        absentToken.setServletPath("/index/repositories/orders/ensure");
        MockHttpServletResponse absentTokenResponse = new MockHttpServletResponse();
        filter.doFilter(absentToken, absentTokenResponse, (request, response) ->
                ((jakarta.servlet.http.HttpServletResponse) response).setStatus(204));
        assertThat(absentTokenResponse.getStatus()).isEqualTo(401);

        MockHttpServletRequest validToken = new MockHttpServletRequest("POST", "/semantic/index/repositories/orders/ensure");
        validToken.setContextPath("/semantic");
        validToken.setServletPath("/index/repositories/orders/ensure");
        validToken.addHeader(IndexerAdminTokenFilter.TOKEN_HEADER, "admin-token");
        MockHttpServletResponse validTokenResponse = new MockHttpServletResponse();
        filter.doFilter(validToken, validTokenResponse, (request, response) ->
                ((jakarta.servlet.http.HttpServletResponse) response).setStatus(204));
        assertThat(validTokenResponse.getStatus()).isEqualTo(204);
    }
}
