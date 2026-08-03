package com.java.semantic.api.security;

import com.java.semantic.api.RequestCorrelationFilter;
import com.java.semantic.api.ApiMonitoringFilter;
import com.java.semantic.mcp.monitoring.McpTransportMonitoringFilter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import tools.jackson.databind.ObjectMapper;

/** 以 /* 註冊而非逐條列舉,新端點預設即受保護 */
@Configuration
@EnableConfigurationProperties(ApiSecurityProperties.class)
public class ApiSecurityConfig {

    @Bean
    public FilterRegistrationBean<RequestCorrelationFilter> requestCorrelationFilter() {
        FilterRegistrationBean<RequestCorrelationFilter> registration =
                new FilterRegistrationBean<>(new RequestCorrelationFilter());
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<ApiMonitoringFilter> apiMonitoringFilter() {
        FilterRegistrationBean<ApiMonitoringFilter> registration =
                new FilterRegistrationBean<>(new ApiMonitoringFilter());
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<McpTransportMonitoringFilter> mcpTransportMonitoringFilter() {
        FilterRegistrationBean<McpTransportMonitoringFilter> registration =
                new FilterRegistrationBean<>(new McpTransportMonitoringFilter());
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 2);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<ApiTokenFilter> apiTokenFilter(
            ApiSecurityProperties properties,
            ObjectMapper objectMapper) {
        FilterRegistrationBean<ApiTokenFilter> registration =
                new FilterRegistrationBean<>(new ApiTokenFilter(properties, objectMapper));
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 3);
        return registration;
    }
}
