package com.java.semantic.repository.config;

import com.java.semantic.indexer.source.DurableSourceFiles;
import java.io.IOException;
import java.nio.file.Path;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RepositoryProperties.class)
public class RepositoryConfiguration {
    @Bean(destroyMethod = "close")
    DurableSourceFiles sourceWriterOwnership(RepositoryProperties properties) throws IOException {
        Path admin = Path.of(properties.getSourceAdminRoot()).toAbsolutePath().normalize();
        Path published = Path.of(properties.getSourcePublishedRoot()).toAbsolutePath().normalize();
        if (!admin.getParent().equals(published.getParent()) || admin.equals(published)) {
            throw new IOException("source roots must be separate siblings on one filesystem");
        }
        return new DurableSourceFiles(admin);
    }
}
