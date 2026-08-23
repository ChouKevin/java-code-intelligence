package com.java.semantic.model.repository;

public final class InvalidRepositoryIdException extends IllegalArgumentException {

    public InvalidRepositoryIdException(String value) {
        super("repository id is invalid: " + value);
    }
}
