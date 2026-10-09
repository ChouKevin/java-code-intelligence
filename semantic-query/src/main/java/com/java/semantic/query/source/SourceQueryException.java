package com.java.semantic.query.source;

public final class SourceQueryException extends RuntimeException {
    public enum Code {
        INVALID_ARGUMENT("Invalid source request"),
        REPOSITORY_NOT_FOUND("Repository not found"),
        SOURCE_NOT_PREPARED("Source is not prepared"),
        REVISION_NOT_PREPARED("Revision is not prepared"),
        SOURCE_NOT_FOUND("Source path not found"),
        SOURCE_UNSUPPORTED("Source content is unsupported"),
        SOURCE_SUMMARY_UNAVAILABLE("Source summary is unavailable"),
        SOURCE_RESULT_TOO_LARGE("A complete source result exceeds the response budget"),
        SOURCE_BUSY("Source search is busy"),
        SOURCE_UNAVAILABLE("Published source is unavailable"),
        SOURCE_TIMEOUT("Source operation timed out");

        private final String description;
        Code(String description) { this.description = description; }
    }

    private final Code code;
    private final java.util.Optional<com.java.semantic.model.source.SourceReadContract.SummaryOutcome> reason;

    public SourceQueryException(Code code) {
        super(code.description);
        this.code = code;
        this.reason = java.util.Optional.empty();
    }

    public SourceQueryException(Code code, Throwable cause) {
        super(code.description, cause);
        this.code = code;
        this.reason = java.util.Optional.empty();
    }

    public SourceQueryException(com.java.semantic.model.source.SourceReadContract.SummaryOutcome reason) {
        super(Code.SOURCE_SUMMARY_UNAVAILABLE.description + ": " + reason.name());
        this.code = Code.SOURCE_SUMMARY_UNAVAILABLE;
        this.reason = java.util.Optional.of(reason);
    }

    public java.util.Optional<com.java.semantic.model.source.SourceReadContract.SummaryOutcome> reason() { return reason; }

    public Code code() { return code; }
}
