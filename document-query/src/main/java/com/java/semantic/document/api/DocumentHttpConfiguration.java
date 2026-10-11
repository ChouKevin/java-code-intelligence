package com.java.semantic.document.api;

import com.java.semantic.document.config.DocumentQueryProperties;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration(proxyBeanMethods = false)
public class DocumentHttpConfiguration {
    @Bean
    FilterRegistrationBean<DocumentTokenFilter> documentTokenFilter(DocumentQueryProperties properties) {
        FilterRegistrationBean<DocumentTokenFilter> registration = new FilterRegistrationBean<>(new DocumentTokenFilter(properties));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setDispatcherTypes(DispatcherType.REQUEST);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
