package com.java.semantic.mcp.dto.source;

import com.java.semantic.identity.MethodTarget;
import com.java.semantic.monitoring.MonitoringField;
import com.java.semantic.monitoring.MonitoringMode;
import com.java.semantic.semantic.application.InternalSourceReferenceResult;
import com.java.semantic.syntax.application.EvidenceSourceQuery;
import com.java.semantic.syntax.application.RevisionBoundSourceSymbolResolution;
import com.java.semantic.syntax.application.SourceSymbolContext;
import com.java.semantic.syntax.domain.ExactSourceDeclarationTarget;
import com.java.semantic.syntax.domain.SourceRange;
import com.java.semantic.syntax.domain.SyntaxPosition;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.Optional;
import java.util.List;

/** source navigation MCP 查詢的 transport DTO */
public final class SourceDiscoveryMcpDtos {

    private SourceDiscoveryMcpDtos() {
        throw new UnsupportedOperationException("utility class");
    }

    /** source symbol resolution 輸入 */
    public record ResolveSymbolInput(
            @MonitoringField(MonitoringMode.VALUE) @NotBlank
            @Pattern(regexp = "^[a-z0-9][a-z0-9._-]{0,63}$") String repoId,
            @MonitoringField(MonitoringMode.VALUE) @NotBlank
            @Pattern(regexp = "^[0-9a-f]{40}$|^FIXTURE$") String expectedRevision,
            @MonitoringField(MonitoringMode.NESTED) @NotNull @Valid SourceSymbolContext context,
            @MonitoringField(MonitoringMode.VALUE) @NotBlank String symbol,
            @MonitoringField(MonitoringMode.NESTED) @NotNull Optional<@Valid SyntaxPosition> position) {
    }

    /** source symbol resolution 結果 */
    public record ResolveSymbolOutput(
            @MonitoringField(MonitoringMode.NESTED) @NotNull @Valid RevisionBoundSourceSymbolResolution result) {
    }

    /** internal source reference 查詢輸入 */
    public record InternalReferencesInput(
            @MonitoringField(MonitoringMode.VALUE) @NotBlank
            @Pattern(regexp = "^[a-z0-9][a-z0-9._-]{0,63}$") String repoId,
            @MonitoringField(MonitoringMode.VALUE) @NotBlank
            @Pattern(regexp = "^[0-9a-f]{40}$|^FIXTURE$") String expectedRevision,
            @MonitoringField(MonitoringMode.NESTED) @NotNull @Valid ExactSourceDeclarationTarget target,
            @MonitoringField(MonitoringMode.VALUE) @NotNull @Min(0) Integer offset,
            @MonitoringField(MonitoringMode.VALUE) @NotNull @Min(1) @Max(100) Integer limit) {
    }

    /** internal source reference 查詢結果 */
    public record InternalReferencesOutput(
            @MonitoringField(MonitoringMode.NESTED) @NotNull @Valid InternalSourceReferenceResult result) {
    }

    /** source segment 查詢輸入 */
    public record SourceSegmentInput(
            @MonitoringField(MonitoringMode.VALUE) @NotBlank
            @Pattern(regexp = "^[a-z0-9][a-z0-9._-]{0,63}$") String repoId,
            @MonitoringField(MonitoringMode.VALUE) @NotBlank
            @Pattern(regexp = "^[0-9a-f]{40}$|^FIXTURE$") String expectedRevision,
            @MonitoringField(MonitoringMode.NESTED) @NotNull @Valid SourceRange location,
            @MonitoringField(MonitoringMode.VALUE) @NotNull @Min(0) @Max(20) Integer contextLines) {
    }

    /** bounded source segment 結果 */
    public record SourceSegmentOutput(
            @MonitoringField(MonitoringMode.VALUE) @NotBlank String repoId,
            @MonitoringField(MonitoringMode.VALUE) @NotBlank String analyzedRevision,
            @MonitoringField(MonitoringMode.NESTED) @NotNull @Valid SourceRange location,
            @MonitoringField(MonitoringMode.OMIT) @NotBlank String content,
            @MonitoringField(MonitoringMode.NESTED) @NotNull Optional<@Valid SourceRange> nextLocation,
            @MonitoringField(MonitoringMode.VALUE) boolean contextTruncated,
            @MonitoringField(MonitoringMode.NESTED) @NotNull List<@Valid SourceSegmentFollowUp> availableFollowUps) {
    }

    /** method source 查詢輸入 */
    public record MethodSourceInput(
            @MonitoringField(MonitoringMode.VALUE) @NotBlank
            @Pattern(regexp = "^[a-z0-9][a-z0-9._-]{0,63}$") String repoId,
            @MonitoringField(MonitoringMode.VALUE) @NotBlank
            @Pattern(regexp = "^[0-9a-f]{40}$|^FIXTURE$") String expectedRevision,
            @MonitoringField(MonitoringMode.NESTED) @NotNull @Valid MethodTarget target) {
    }

    /** method source 結果 */
    public record MethodSourceOutput(
            @MonitoringField(MonitoringMode.VALUE) @NotBlank String repoId,
            @MonitoringField(MonitoringMode.VALUE) @NotBlank String analyzedRevision,
            @MonitoringField(MonitoringMode.NESTED) @NotNull @Valid SourceRange declarationLocation,
            @MonitoringField(MonitoringMode.NESTED) @NotNull @Valid SourceSegment segment,
            @MonitoringField(MonitoringMode.VALUE) boolean implementationDiscoveryEligible,
            @MonitoringField(MonitoringMode.NESTED) @NotNull List<@Valid SourceSegmentFollowUp> availableFollowUps) {
    }

    /** 可續讀的原始碼區段 */
    public record SourceSegment(
            @MonitoringField(MonitoringMode.NESTED) @NotNull @Valid SourceRange location,
            @MonitoringField(MonitoringMode.OMIT) @NotBlank String content,
            @MonitoringField(MonitoringMode.NESTED) @NotNull Optional<@Valid SourceRange> nextLocation,
            @MonitoringField(MonitoringMode.VALUE) boolean contextTruncated) {
    }

    /** evidence source 查詢輸入 */
    public record EvidenceSourceInput(
            @MonitoringField(MonitoringMode.VALUE) @NotBlank
            @Pattern(regexp = "^[a-z0-9][a-z0-9._-]{0,63}$") String repoId,
            @MonitoringField(MonitoringMode.VALUE) @NotBlank
            @Pattern(regexp = "^[0-9a-f]{40}$|^FIXTURE$") String expectedRevision,
            @MonitoringField(MonitoringMode.NESTED) @NotNull @Valid EvidenceSourceQuery.EvidenceIdentity identity) {
    }

    /** evidence source 結果 */
    public record EvidenceSourceOutput(
            @MonitoringField(MonitoringMode.VALUE) @NotBlank String repoId,
            @MonitoringField(MonitoringMode.VALUE) @NotBlank String analyzedRevision,
            @MonitoringField(MonitoringMode.NESTED) @NotNull @Valid EvidenceSourceQuery.EvidenceIdentity identity,
            @MonitoringField(MonitoringMode.NESTED) @NotNull @Valid SourceRange location,
            @MonitoringField(MonitoringMode.NESTED) @NotNull @Valid SourceSegment segment,
            @MonitoringField(MonitoringMode.NESTED) @NotNull List<@Valid SourceSegmentFollowUp> availableFollowUps) {
    }

    /** 指向下一個 MCP source segment 查詢的 typed follow-up */
    public record SourceSegmentFollowUp(
            @MonitoringField(MonitoringMode.VALUE) @NotBlank String toolName,
            @MonitoringField(MonitoringMode.NESTED) @NotNull @Valid SourceSegmentInput arguments) {
    }
}
