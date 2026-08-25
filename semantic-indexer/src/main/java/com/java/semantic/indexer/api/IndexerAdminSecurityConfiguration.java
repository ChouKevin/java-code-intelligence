package com.java.semantic.indexer.api;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/** Registers Indexer-only mutation protection. */
@Configuration
@EnableConfigurationProperties(IndexerAdminSecurityProperties.class)
public class IndexerAdminSecurityConfiguration {
    @Bean
    public FilterRegistrationBean<IndexerAdminTokenFilter> indexerAdminTokenFilter(IndexerAdminSecurityProperties properties) {
        FilterRegistrationBean<IndexerAdminTokenFilter> registration = new FilterRegistrationBean<>(new IndexerAdminTokenFilter(properties));
        registration.addUrlPatterns("/index/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
