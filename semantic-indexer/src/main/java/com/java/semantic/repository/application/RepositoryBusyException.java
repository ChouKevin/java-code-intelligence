package com.java.semantic.repository.application;

/** 儲存庫鎖未能在期限內取得 */
public class RepositoryBusyException extends RuntimeException {

    public RepositoryBusyException(String message) {
        super(message);
    }
}
