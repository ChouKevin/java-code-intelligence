package com.java.semantic.query.application;

public final class RepositoryNotFoundException extends RuntimeException {
    public RepositoryNotFoundException() {
        super("REPOSITORY_NOT_FOUND");
    }
}
