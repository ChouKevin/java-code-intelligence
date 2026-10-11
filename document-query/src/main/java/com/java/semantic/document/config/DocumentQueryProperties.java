package com.java.semantic.document.config;

import com.java.semantic.document.application.DocumentContract.CollectionPurpose;
import com.java.semantic.model.repository.RepositoryId;
import java.nio.file.Path;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "document")
public record DocumentQueryProperties(String repositoryId, Path gitDirectory, Path publicationFile,
        CollectionPurpose collectionPurpose, String apiToken) {
    public DocumentQueryProperties {
        repositoryId = new RepositoryId(repositoryId).value();
        Objects.requireNonNull(collectionPurpose, "document.collection-purpose is required");
        if (Objects.isNull(gitDirectory) || !gitDirectory.isAbsolute()
                || Objects.isNull(publicationFile) || !publicationFile.isAbsolute()) {
            throw new IllegalArgumentException("Document storage paths must be absolute");
        }
        gitDirectory = gitDirectory.normalize();
        publicationFile = publicationFile.normalize();
        if (Objects.isNull(apiToken) || apiToken.isBlank()) {
            throw new IllegalArgumentException("Document API token is required");
        }
    }
    @Override public String toString() { return "DocumentQueryProperties[redacted]"; }
}
