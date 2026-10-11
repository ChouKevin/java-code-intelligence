package com.java.semantic.document.application;

public final class DocumentFailure extends RuntimeException {
    public enum Code {
        INVALID_ARGUMENT, DOCUMENT_REPOSITORY_NOT_FOUND, DOCUMENT_REVISION_NOT_AVAILABLE,
        DOCUMENT_NOT_FOUND, DOCUMENT_SECTION_NOT_FOUND, DOCUMENT_CATALOG_INVALID,
        DOCUMENT_CAPACITY_EXCEEDED, DOCUMENT_RESULT_TOO_LARGE, DOCUMENT_BUSY,
        DOCUMENT_TIMEOUT, DOCUMENT_UNAVAILABLE
    }
    private final Code code;
    public DocumentFailure(Code code) { super(message(code)); this.code = code; }
    public Code code() { return code; }
    private static String message(Code code) {
        return switch (code) {
            case INVALID_ARGUMENT -> "Invalid document query arguments.";
            case DOCUMENT_REPOSITORY_NOT_FOUND -> "Document repository is not available.";
            case DOCUMENT_REVISION_NOT_AVAILABLE -> "Exact document revision is not retained.";
            case DOCUMENT_NOT_FOUND -> "Document is not in the selected catalog.";
            case DOCUMENT_SECTION_NOT_FOUND -> "Exact document section is not available.";
            case DOCUMENT_CATALOG_INVALID -> "Document publication is invalid.";
            case DOCUMENT_CAPACITY_EXCEEDED -> "Document publication exceeds a fixed capacity bound.";
            case DOCUMENT_RESULT_TOO_LARGE -> "Document result exceeds the response bound.";
            case DOCUMENT_BUSY -> "Document operation capacity is occupied.";
            case DOCUMENT_TIMEOUT -> "Document operation deadline expired.";
            case DOCUMENT_UNAVAILABLE -> "Document publication is unavailable.";
        };
    }
}
