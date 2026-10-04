package com.java.semantic;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Independent mutation process: source-only Git preparation and private transports. */
@SpringBootApplication(scanBasePackages = {"com.java.semantic.indexer.source", "com.java.semantic.indexer.job",
        "com.java.semantic.indexer.application", "com.java.semantic.indexer.api",
        "com.java.semantic.indexer.mcp", "com.java.semantic.indexer.config",
        "com.java.semantic.repository.config"})
public class SemanticIndexerApplication {
    public static void main(String[] args) {
        SpringApplication.run(SemanticIndexerApplication.class, args);
    }
}
