package com.java.semantic.syntax.application;

import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;
import com.java.semantic.syntax.domain.MapperFragmentIdentity;
import com.java.semantic.syntax.domain.MapperStatementIdentity;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 固定 revision 的 inline 或 segment-authorized exact content 結果 */
public record ExactContentResult(
        RepositoryId repositoryId,
        RepositoryRevision analyzedRevision,
        List<ContentVariant> variants) {

    public ExactContentResult {
        repositoryId = Objects.requireNonNull(repositoryId, "repositoryId is required");
        analyzedRevision = Objects.requireNonNull(analyzedRevision, "analyzedRevision is required");
        variants = List.copyOf(Objects.requireNonNull(variants, "variants are required"));
        if (variants.isEmpty()) {
            throw new IllegalArgumentException("exact content variants are required");
        }
    }

    /** 一筆 method source、mapper statement 或 mapper fragment 的完整 typed 證據 */
    public record ContentVariant(
            Optional<MapperStatementIdentity> statementIdentity,
            Optional<MapperFragmentIdentity> fragmentIdentity,
            List<IncludeResolution> includeResolutions,
            Content content) {

        public ContentVariant {
            statementIdentity = Objects.requireNonNull(statementIdentity, "statementIdentity is required");
            fragmentIdentity = Objects.requireNonNull(fragmentIdentity, "fragmentIdentity is required");
            includeResolutions = List.copyOf(Objects.requireNonNull(
                    includeResolutions, "includeResolutions are required"));
            content = Objects.requireNonNull(content, "content is required");
            if (statementIdentity.isPresent() && fragmentIdentity.isPresent()) {
                throw new IllegalArgumentException("content variant cannot contain both mapper identities");
            }
            if (!statementIdentity.isPresent() && includeResolutions.size() > 0) {
                throw new IllegalArgumentException("only mapper statements may contain include resolutions");
            }
        }

        public ContentVariant(
                Optional<MapperStatementIdentity> statementIdentity,
                Optional<MapperFragmentIdentity> fragmentIdentity,
                Content content) {
            this(statementIdentity, fragmentIdentity, List.of(), content);
        }
    }

    /**
     * mapper statement extraction 證據中的單一 include resolution
     * ambiguous 時保留 canonical ordered 的每個完整 fragment identity
     */
    public record IncludeResolution(
            String refId,
            IncludeResolutionStatus status,
            List<MapperFragmentIdentity> fragmentIdentities) {

        public IncludeResolution {
            refId = requiredText(refId, "refId");
            status = Objects.requireNonNull(status, "status is required");
            fragmentIdentities = List.copyOf(Objects.requireNonNull(
                    fragmentIdentities, "fragmentIdentities are required"));
            if (status == IncludeResolutionStatus.RESOLVED && fragmentIdentities.size() != 1) {
                throw new IllegalArgumentException("resolved include requires one fragment identity");
            }
            if (status == IncludeResolutionStatus.AMBIGUOUS && fragmentIdentities.size() < 2) {
                throw new IllegalArgumentException("ambiguous include requires multiple fragment identities");
            }
            if (status == IncludeResolutionStatus.UNRESOLVED && fragmentIdentities.size() > 0) {
                throw new IllegalArgumentException("unresolved include cannot contain fragment identities");
            }
        }
    }

    /** typed include resolution 狀態 */
    public enum IncludeResolutionStatus {
        RESOLVED,
        AMBIGUOUS,
        UNRESOLVED
    }

    /** 不截斷的 inline content 或可安全續讀的 oversized content metadata */
    public record Content(
            Optional<String> inlineContent,
            Optional<String> contentRef,
            int utf8ByteCount,
            int segmentCount) {

        public Content {
            inlineContent = Objects.requireNonNull(inlineContent, "inlineContent is required");
            contentRef = Objects.requireNonNull(contentRef, "contentRef is required");
            if (utf8ByteCount < 0) {
                throw new IllegalArgumentException("utf8ByteCount must not be negative");
            }
            boolean inline = inlineContent.isPresent();
            boolean referenced = contentRef.isPresent();
            if (inline == referenced) {
                throw new IllegalArgumentException("content must be inline or reference-backed");
            }
            if (inline) {
                String value = inlineContent.orElseThrow();
                if (value.getBytes(StandardCharsets.UTF_8).length != utf8ByteCount || segmentCount != 0) {
                    throw new IllegalArgumentException("inline content metadata is inconsistent");
                }
            } else if (segmentCount < 1) {
                throw new IllegalArgumentException("referenced content requires segments");
            }
        }
    }

    private static String requiredText(String value, String fieldName) {
        String text = Objects.requireNonNull(value, fieldName + " is required");
        if (text.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return text;
    }
}
