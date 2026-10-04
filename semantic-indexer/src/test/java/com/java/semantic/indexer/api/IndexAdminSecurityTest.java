package com.java.semantic.indexer.api;

import com.java.semantic.indexer.application.IndexerPreparationFacade;
import com.java.semantic.indexer.job.SourcePreparationJob;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class IndexAdminSecurityTest {
    @Test
    void private_source_http_and_mcp_reject_query_token_and_accept_only_admin_token() throws Exception {
        IndexerAdminSecurityProperties properties = new IndexerAdminSecurityProperties();
        properties.setAdminToken("admin-secret");
        IndexerAdminTokenFilter filter = new IndexerAdminTokenFilter(properties);
        for (String path : new String[] {"/index/repositories/orders/source", "/index/repositories/orders/jobs", "/mcp"}) {
            for (String token : new String[] {"", "query-token", "admin-secret"}) {
                MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
                if (!token.isEmpty()) {
                    request.addHeader(IndexerAdminTokenFilter.TOKEN_HEADER, token);
                }
                MockHttpServletResponse response = new MockHttpServletResponse();
                filter.doFilter(request, response, (incoming, outgoing) ->
                        ((jakarta.servlet.http.HttpServletResponse) outgoing).setStatus(204));
                assertThat(response.getStatus()).isEqualTo(token.equals("admin-secret") ? 204 : 401);
                assertThat(response.getContentAsString()).doesNotContain("admin-secret");
            }
        }
    }

    @Test
    void unset_admin_token_fails_closed_even_with_supplied_header() throws Exception {
        IndexerAdminTokenFilter filter = new IndexerAdminTokenFilter(new IndexerAdminSecurityProperties());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/index/repositories/orders/source");
        request.addHeader(IndexerAdminTokenFilter.TOKEN_HEADER, "admin-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (incoming, outgoing) ->
                ((jakarta.servlet.http.HttpServletResponse) outgoing).setStatus(204));
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void context_path_cannot_bypass_private_token_filter_and_job_response_redacts_secrets() throws Exception {
        IndexerAdminSecurityProperties properties = new IndexerAdminSecurityProperties();
        properties.setAdminToken("admin-secret");
        IndexerAdminTokenFilter filter = new IndexerAdminTokenFilter(properties);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/semantic/index/repositories/orders/jobs");
        request.setContextPath("/semantic");
        request.setServletPath("/index/repositories/orders/jobs");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (incoming, outgoing) ->
                ((jakarta.servlet.http.HttpServletResponse) outgoing).setStatus(204));
        assertThat(response.getStatus()).isEqualTo(401);
        SourcePreparationJob job = new SourcePreparationJob(1, "opaque-job", "orders",
                "00000000-0000-0000-0000-000000000001", Optional.empty(), "main",
                SourcePreparationJob.Phase.ACCEPTED, Instant.EPOCH, Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty());
        String json = JsonMapper.builder().build().writeValueAsString(IndexerPreparationFacade.result(job));
        assertThat(json).doesNotContain("admin-secret", "private-root", "https://git.example");
        assertThat(properties.toString()).doesNotContain("admin-secret");
    }
}
