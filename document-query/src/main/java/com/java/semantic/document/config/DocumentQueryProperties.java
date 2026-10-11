package com.java.semantic.document.config;

import com.java.semantic.document.application.DocumentContract.CollectionPurpose;
import java.nio.file.Path;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "document")
public record DocumentQueryProperties(String repositoryId, Path gitDirectory, Path publicationFile,
        CollectionPurpose collectionPurpose, String apiToken) {
    public DocumentQueryProperties {
        DocumentStorage storage = new DocumentStorage(repositoryId, gitDirectory, publicationFile, collectionPurpose);
        repositoryId = storage.repositoryId();
        gitDirectory = storage.gitDirectory();
        publicationFile = storage.publicationFile();
        if (Objects.isNull(apiToken) || apiToken.isBlank()) {
            throw new IllegalArgumentException("Document API token is required");
        }
    }
    public DocumentStorage storage() {
        return new DocumentStorage(repositoryId, gitDirectory, publicationFile, collectionPurpose);
    }
    @Override public String toString() { return "DocumentQueryProperties[redacted]"; }
}
