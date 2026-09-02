package com.java.semantic.api;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/** Registers the Query-only HTTP and MCP security boundary. */
@Configuration
@EnableConfigurationProperties(QuerySecurityProperties.class)
public class QuerySecurityConfiguration {
    @Bean
    public FilterRegistrationBean<QueryRequestMonitoringFilter> queryRequestMonitoringFilter() {
        FilterRegistrationBean<QueryRequestMonitoringFilter> registration = new FilterRegistrationBean<>(new QueryRequestMonitoringFilter());
        registration.addUrlPatterns("/api/v1/*", "/mcp");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<QueryTokenFilter> queryTokenFilter(QuerySecurityProperties properties) {
        FilterRegistrationBean<QueryTokenFilter> registration = new FilterRegistrationBean<>(new QueryTokenFilter(properties));
        registration.addUrlPatterns("/api/v1/*", "/mcp");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        return registration;
    }
}
