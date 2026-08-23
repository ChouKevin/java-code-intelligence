package com.java.semantic.repository.domain;

/** repoId 未通過驗證 */
public class InvalidRepositoryIdException extends RuntimeException {

    public InvalidRepositoryIdException(String value) {
        super("repositoryId is not a valid identifier: " + value);
    }
}
