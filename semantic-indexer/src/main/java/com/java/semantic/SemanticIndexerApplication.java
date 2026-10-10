package com.java.semantic;

import com.java.semantic.indexer.index.IndexWorkerMain;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Independent mutation process: Git source preparation, optional syntax indexes and private transports. */
@SpringBootApplication(scanBasePackages = {"com.java.semantic.indexer.source", "com.java.semantic.indexer.job",
        "com.java.semantic.indexer.application", "com.java.semantic.indexer.api",
        "com.java.semantic.indexer.mcp", "com.java.semantic.indexer.config", "com.java.semantic.indexer.index",
        "com.java.semantic.repository.config"})
public class SemanticIndexerApplication {
    public static void main(String[] args) {
        if (args.length == 2 && args[0].equals("--index-worker")) {
            IndexWorkerMain.main(new String[] {args[1]});
            return;
        }
        SpringApplication.run(SemanticIndexerApplication.class, args);
    }
}
