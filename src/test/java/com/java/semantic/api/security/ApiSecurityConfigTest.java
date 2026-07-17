package com.java.semantic.api.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;

import static org.assertj.core.api.Assertions.assertThat;

class ApiSecurityConfigTest {

    @Test
    void should_register_filter_globally_when_configuring_api_security() {
        ApiSecurityConfig config = new ApiSecurityConfig();
        ApiSecurityProperties properties = new ApiSecurityProperties();

        FilterRegistrationBean<ApiTokenFilter> registration = config.apiTokenFilter(properties);

        assertThat(registration.getUrlPatterns()).containsExactly("/*");
        assertThat(registration.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
        assertThat(registration.getFilter()).isInstanceOf(ApiTokenFilter.class);
    }
}
