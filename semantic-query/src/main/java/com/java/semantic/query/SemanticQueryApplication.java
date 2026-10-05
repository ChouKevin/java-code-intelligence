package com.java.semantic.query;

import com.java.semantic.query.application.SemanticQueryFacade;
import com.java.semantic.query.config.SourceAccessProperties;
import com.java.semantic.query.source.LocalRepositorySourceService;
import com.java.semantic.query.source.LocalSourceRevisionCatalog;
import com.java.semantic.query.source.RepositorySourcePort;
import com.java.semantic.query.source.SourceRevisionCatalog;
import com.java.semantic.query.source.SourceReadLocks;
import java.util.List;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import tools.jackson.databind.ObjectMapper;

@SpringBootApplication(scanBasePackages = {"com.java.semantic.query", "com.java.semantic.api", "com.java.semantic.mcp"})
@EnableConfigurationProperties(SourceAccessProperties.class)
public class SemanticQueryApplication {

    public static void main(String[] args) {
        SpringApplication.run(SemanticQueryApplication.class, args);
    }

    @Bean
    SourceRevisionCatalog sourceRevisionCatalog(SourceAccessProperties properties, ObjectMapper mapper, SourceReadLocks locks) {
        return new LocalSourceRevisionCatalog(properties, mapper, locks);
    }

    @Bean
    SourceReadLocks sourceReadLocks(SourceAccessProperties properties) {
        return new SourceReadLocks(properties.publishedRoot());
    }

    @Bean
    RepositorySourcePort repositorySourcePort(SourceAccessProperties properties, ObjectMapper mapper) {
        return new LocalRepositorySourceService(properties, mapper);
    }

    @Bean
    SemanticQueryFacade semanticQueryFacade(SourceRevisionCatalog catalog, RepositorySourcePort source,
            SourceAccessProperties properties) {
        return new SemanticQueryFacade(catalog, source, properties.maxActiveSearches());
    }

    @Bean
    static BeanFactoryPostProcessor rejectLegacyRestrictions(Environment environment) {
        return beanFactory -> {
            Binder binder = Binder.get(environment);
            for (String name : List.of("forbidden-repositories", "forbidden-packages", "forbidden-classes",
                    "forbidden-methods")) {
                String key = "semantic.query.read-policy." + name;
                List<String> restrictions = binder.bind(key, Bindable.listOf(String.class)).orElse(List.of());
                if (!restrictions.isEmpty()) {
                    throw new IllegalStateException("Remove legacy Query read-policy restrictions before starting source-only Query: " + key);
                }
            }
        };
    }
}
