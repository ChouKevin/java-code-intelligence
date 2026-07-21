package com.java.semantic.callgraph.domain;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

public record RevisionBoundAnalysisResult<T>(
        AnalysisStatus status,
        T data,
        List<AnalysisWarning> warnings,
        List<AnalysisError> errors,
        AnalysisMetadata metadata,
        String analyzedRevision) {

    public RevisionBoundAnalysisResult {
        warnings = List.copyOf(warnings);
        errors = List.copyOf(errors);
    }

    public <R> RevisionBoundAnalysisResult<R> mapData(Function<? super T, ? extends R> mapper) {
        Objects.requireNonNull(mapper, "mapper is required");
        R mappedData = Objects.nonNull(data) ? mapper.apply(data) : null;
        return new RevisionBoundAnalysisResult<>(
                status,
                mappedData,
                warnings,
                errors,
                metadata,
                analyzedRevision);
    }

    public static <T> RevisionBoundAnalysisResult<T> success(
            T data,
            AnalysisMetadata metadata,
            String analyzedRevision) {
        return new RevisionBoundAnalysisResult<>(
                AnalysisStatus.SUCCESS,
                data,
                List.of(),
                List.of(),
                metadata,
                analyzedRevision);
    }

    public static <T> RevisionBoundAnalysisResult<T> partial(
            T data,
            List<AnalysisWarning> warnings,
            List<AnalysisError> errors,
            AnalysisMetadata metadata,
            String analyzedRevision) {
        return new RevisionBoundAnalysisResult<>(
                AnalysisStatus.PARTIAL,
                data,
                warnings,
                errors,
                metadata,
                analyzedRevision);
    }

    public static <T> RevisionBoundAnalysisResult<T> failed(
            List<AnalysisError> errors,
            AnalysisMetadata metadata,
            String analyzedRevision) {
        return new RevisionBoundAnalysisResult<>(
                AnalysisStatus.FAILED,
                null,
                List.of(),
                errors,
                metadata,
                analyzedRevision);
    }

    public static <T> RevisionBoundAnalysisResult<T> businessReadForbidden(
            List<AnalysisWarning> warnings,
            AnalysisMetadata metadata,
            String analyzedRevision) {
        return new RevisionBoundAnalysisResult<>(
                AnalysisStatus.BUSINESS_READ_FORBIDDEN,
                null,
                warnings,
                List.of(),
                metadata,
                analyzedRevision);
    }
}
