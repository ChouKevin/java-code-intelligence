package com.java.semantic.config;

import org.springdoc.core.configuration.SpringDocConfiguration;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.core.providers.ObjectMapperProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Provides Swagger UI infrastructure while the generated OpenAPI endpoint remains disabled. */
@Configuration
@EnableConfigurationProperties(SpringDocConfigProperties.class)
public class SemanticSwaggerConfiguration implements WebMvcConfigurer {

    @Bean
    public SpringDocConfiguration springDocConfiguration() {
        return new SpringDocConfiguration();
    }

    @Bean
    public ObjectMapperProvider springdocObjectMapperProvider(SpringDocConfigProperties properties) {
        return new ObjectMapperProvider(properties);
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/openapi/**")
                .addResourceLocations("classpath:/openapi/");
    }
}
