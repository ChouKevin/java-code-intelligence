package com.java.semantic.repository.application;

/** Git 或 fixture 工作樹操作失敗 */
public class RepositoryMutationException extends RuntimeException {

    public RepositoryMutationException(String message) {
        super(message);
    }

    public RepositoryMutationException(String message, Throwable cause) {
        super(message, cause);
    }
}
