package com.java.semantic.trie;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiPathNormalizerTest {

    @Test
    void should_keep_wildcard_tokens_when_normalized_repeatedly() {
        NormalizedApiPath once = ApiPathNormalizer.normalize("/files/{*}/{**}", "GET");
        NormalizedApiPath twice = ApiPathNormalizer.normalize(once.path(), once.httpMethod());

        assertThat(once).isEqualTo(new NormalizedApiPath("/files/{*}/{**}", "GET"));
        assertThat(twice).isEqualTo(once);
    }

    @Test
    void should_canonicalize_parameter_forms_when_path_contains_templates() {
        assertThat(ApiPathNormalizer.normalize("/orders/{id}/:line/<field>/**", "get").path())
                .isEqualTo("/orders/{*}/{*}/{*}/{**}");
    }

    @Test
    void should_preserve_reserved_escapes_when_path_contains_percent_encoding() {
        assertThat(ApiPathNormalizer.normalize("/files/a%2fb/%7E/%7Bid%7D/%C3%A9", "GET").path())
                .isEqualTo("/files/a%2Fb/~/%7Bid%7D/%C3%A9");
    }

    @Test
    void should_extract_method_when_path_contains_method_prefix() {
        assertThat(ApiPathNormalizer.normalize("[post]: /orders/42?debug=true#part", ""))
                .isEqualTo(new NormalizedApiPath("/orders/42", "POST"));
    }

    @Test
    void should_reject_conflict_when_prefix_and_argument_methods_differ() {
        assertThatThrownBy(() -> ApiPathNormalizer.normalize("GET /orders/42", "POST"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Conflicting HTTP methods");
    }

    @Test
    void should_accept_method_when_http_method_is_trace() {
        assertThat(ApiPathNormalizer.normalize("/orders/42", "TRACE"))
                .isEqualTo(new NormalizedApiPath("/orders/42", "TRACE"));
        assertThat(ApiPathNormalizer.normalize("TRACE /orders/42", ""))
                .isEqualTo(new NormalizedApiPath("/orders/42", "TRACE"));
    }

    @Test
    void should_reject_method_when_http_method_is_connect() {
        assertThatThrownBy(() -> ApiPathNormalizer.normalize("/orders/42", "CONNECT"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported HTTP method");
    }

    @Test
    void should_reject_malformed_escape_when_path_is_absolute_url() {
        assertThatThrownBy(() -> ApiPathNormalizer.normalize("https://example.internal/a%zz", "GET"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void should_keep_malformed_escape_when_path_is_relative() {
        assertThat(ApiPathNormalizer.normalize("/a%zz", "GET").path()).isEqualTo("/a%zz");
    }

    @Test
    void should_ignore_query_and_fragment_when_absolute_url_has_no_path() {
        assertThat(ApiPathNormalizer.normalize(
                "https://example.internal?redirect=/orders/42#section/orders/42", "GET").path())
                .isEqualTo("/");
    }

    @Test
    void should_reject_url_when_http_scheme_has_no_authority() {
        assertThatThrownBy(() -> ApiPathNormalizer.normalize("https:/orders/42", "GET"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("absolute URL");
        assertThatThrownBy(() -> ApiPathNormalizer.normalize("https:///orders/42", "GET"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("absolute URL");
    }

    @Test
    void should_reject_url_when_http_uri_is_opaque() {
        assertThatThrownBy(() -> ApiPathNormalizer.normalize("https:opaque", "GET"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("absolute URL");
    }

    @Test
    void should_reject_url_when_authority_has_no_valid_host() {
        assertThatThrownBy(() -> ApiPathNormalizer.normalize("https://exa_mple/orders/42", "GET"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("absolute URL");
        assertThatThrownBy(() -> ApiPathNormalizer.normalize("https://user@@example.com/orders/42", "GET"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("absolute URL");
    }

    @Test
    void should_return_root_when_valid_authority_url_has_no_path() {
        assertThat(ApiPathNormalizer.normalize("https://example.internal", "GET"))
                .isEqualTo(new NormalizedApiPath("/", "GET"));
    }
}
