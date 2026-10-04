package com.java.semantic.query.source;

public final class SourceQueryException extends RuntimeException {
    public enum Code {
        INVALID_ARGUMENT("Invalid source request"),
        REPOSITORY_NOT_FOUND("Repository not found"),
        SOURCE_NOT_PREPARED("Source is not prepared"),
        REVISION_NOT_PREPARED("Revision is not prepared"),
        SOURCE_NOT_FOUND("Source path not found"),
        SOURCE_UNSUPPORTED("Source content is unsupported"),
        SOURCE_BUSY("Source search is busy"),
        SOURCE_UNAVAILABLE("Published source is unavailable"),
        SOURCE_TIMEOUT("Source operation timed out");

        private final String description;
        Code(String description) { this.description = description; }
    }

    private final Code code;

    public SourceQueryException(Code code) {
        super(code.description);
        this.code = code;
    }

    public SourceQueryException(Code code, Throwable cause) {
        super(code.description, cause);
        this.code = code;
    }

    public Code code() { return code; }
}
