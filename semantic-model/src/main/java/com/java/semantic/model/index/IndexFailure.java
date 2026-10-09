package com.java.semantic.model.index;

import java.util.Objects;

/** Internal causes stay private; transports expose only the fixed safe recovery contract. */
public final class IndexFailure extends RuntimeException {
    public enum Code {
        INDEX_NOT_CURRENT("New indexes require the exact published current revision. Refresh the configured default branch explicitly, then prepare its resolved revision."),
        INDEX_NOT_PREPARED("No index is published for this exact source revision. Use source reads or an available retained compatible generation. An authorized operator may prepare this exact revision only when it is the published current."),
        INDEX_UNAVAILABLE("The index cannot be read. Use source reads or an available retained compatible generation. An operator may inspect the failure; rebuilding this exact revision is allowed only while it is the published current."),
        INDEX_INCOMPATIBLE("The index schema or parser profile is incompatible. Use source reads or a retained compatible generation. An authorized operator may rebuild this exact revision with matching Indexer and Query versions only while it is the published current."),
        INDEX_GENERATION_EXPIRED("The requested index generation is no longer published. Restart the query in the same source context, without reusing its cursor."),
        INDEX_RESULT_TOO_LARGE("A complete result item or required metadata exceeds the response budget. Narrow the query or use read_source for a known path."),
        INDEX_BUSY("The shared search limit is in use. Retry this same read-only context according to client policy."),
        INDEX_TIMEOUT("The index operation exceeded its deadline. Source context and generation were not silently changed.");

        private final String advice;
        Code(String advice) { this.advice = advice; }
        public String advice() { return advice; }
    }

    private final Code code;

    public IndexFailure(Code code) {
        super(Objects.requireNonNull(code, "index failure code").advice());
        this.code = code;
    }

    public IndexFailure(Code code, Throwable cause) {
        super(Objects.requireNonNull(code, "index failure code").advice(), cause);
        this.code = code;
    }

    public Code code() { return code; }

    /** An initialization wrapper must not disguise VM-fatal failures as ordinary index unavailability. */
    public static IndexFailure nativeUnavailable(LinkageError failure) {
        Throwable cause = failure;
        while (Objects.nonNull(cause)) {
            if (cause instanceof Error fatal && !(fatal instanceof LinkageError)) throw fatal;
            Throwable next = cause.getCause();
            if (next == cause) break;
            cause = next;
        }
        return new IndexFailure(Code.INDEX_UNAVAILABLE, failure);
    }
}
