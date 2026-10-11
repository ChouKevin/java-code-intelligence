package com.java.semantic.document.config;

import com.java.semantic.document.application.DocumentContract.CollectionPurpose;
import com.java.semantic.model.repository.RepositoryId;
import java.nio.file.Path;
import java.util.Objects;

/** Storage identity shared by the read-only server and explicit offline operator. */
public record DocumentStorage(String repositoryId, Path gitDirectory, Path publicationFile,
        CollectionPurpose collectionPurpose) {
    public DocumentStorage {
        repositoryId = new RepositoryId(repositoryId).value();
        Objects.requireNonNull(collectionPurpose, "document.collection-purpose is required");
        if (Objects.isNull(gitDirectory) || !gitDirectory.isAbsolute()
                || Objects.isNull(publicationFile) || !publicationFile.isAbsolute()) {
            throw new IllegalArgumentException("Document storage paths must be absolute");
        }
        gitDirectory = gitDirectory.normalize();
        publicationFile = publicationFile.normalize();
    }
    @Override public String toString() { return "DocumentStorage[redacted]"; }
}
