package com.java.semantic;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Indexer runs administrative index mutations only; read APIs and MCP live in semantic-query. */
@SpringBootApplication
public class SemanticIndexerApplication {

    public static void main(String[] args) {
        SpringApplication.run(SemanticIndexerApplication.class, args);
    }
}
