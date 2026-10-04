package com.java.semantic.model.source;

import com.java.semantic.model.support.ModelValidation;
import java.time.Instant;
import java.util.Objects;

public record PreparedRevision(SourceContext context, String manifestDigest, String firstPublishedJobId,
        Instant preparedAt) {

    public PreparedRevision {
        context = Objects.requireNonNull(context, "source context");
        manifestDigest = ModelValidation.sha256(manifestDigest, "manifest digest");
        firstPublishedJobId = ModelValidation.requiredText(firstPublishedJobId, "first published job id");
        preparedAt = Objects.requireNonNull(preparedAt, "prepared at");
    }
}
