package com.java.semantic.syntax.application;

import com.java.semantic.repository.application.RepositoryApplicationService;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.syntax.domain.MapperEvidenceIndex;
import com.java.semantic.syntax.domain.RepositorySyntax;
import com.java.semantic.syntax.domain.RevisionBoundRepositorySyntaxProvider;
import com.java.semantic.syntax.domain.RevisionPinnedSourceRangeReader;
import com.java.semantic.model.codefact.SourceRange;
import com.java.semantic.model.codefact.SourceRangeSegment;
import com.java.semantic.model.codefact.SyntaxPosition;
import java.util.Objects;
import java.util.Optional;

/**
 * 在固定 revision 讀取受 64 KiB 限制且由 RepositorySyntax 授權的來源範圍
 * 此服務是 Agent 收到 discovery 結果後取得有限上下文的續讀路徑
 */
public final class SourceSegmentApplicationService {

    private final RepositoryApplicationService repositoryApplicationService;
    private final RevisionBoundRepositorySyntaxProvider repositorySyntaxProvider;
    private final RevisionPinnedSourceRangeReader sourceRangeReader;

    public SourceSegmentApplicationService(
            RepositoryApplicationService repositoryApplicationService,
            RevisionBoundRepositorySyntaxProvider repositorySyntaxProvider,
            RevisionPinnedSourceRangeReader sourceRangeReader) {
        this.repositoryApplicationService = Objects.requireNonNull(
                repositoryApplicationService, "repositoryApplicationService is required");
        this.repositorySyntaxProvider = Objects.requireNonNull(
                repositorySyntaxProvider, "repositorySyntaxProvider is required");
        this.sourceRangeReader = Objects.requireNonNull(sourceRangeReader, "sourceRangeReader is required");
    }

    /** 在 expected revision 的讀鎖內完成安全檔案讀取與 bounded materialization */
    public SourceSegmentResult read(SourceSegmentQuery query) {
        SourceSegmentQuery requiredQuery = Objects.requireNonNull(query, "query is required");
        return repositoryApplicationService.withSnapshot(
                requiredQuery.repositoryId(),
                Optional.of(requiredQuery.expectedRevision()),
                snapshot -> readSnapshot(snapshot, requiredQuery));
    }

    private SourceSegmentResult readSnapshot(RepositorySnapshot snapshot, SourceSegmentQuery query) {
        SourceRange authority = authorize(repositorySyntaxProvider.get(snapshot), query);
        SourceRangeSegment segment = sourceRangeReader.read(
                snapshot, query.location(), query.contextLines(), authority);
        return new SourceSegmentResult(
                snapshot.repositoryId(),
                snapshot.revision(),
                segment.location(),
                segment.content(),
                segment.nextLocation(),
                segment.contextTruncated());
    }

    private SourceRange authorize(RepositorySyntax syntax, SourceSegmentQuery query) {
        RepositorySyntax requiredSyntax = Objects.requireNonNull(syntax, "syntax is required");
        SourceSegmentQuery requiredQuery = Objects.requireNonNull(query, "query is required");
        SourceRange requiredRange = requiredQuery.location();
        Optional<SourceRange> javaAuthority = requiredSyntax.sourceTypes().stream()
                .map(metadata -> metadata.declaration().declarationLocation())
                .filter(authority -> contains(authority, requiredRange))
                .min(this::mostSpecificAuthority);
        if (javaAuthority.isPresent()) {
            return javaAuthority.orElseThrow();
        }
        Optional<SourceRange> mapperAuthority = requiredSyntax.mapperEvidenceIndex()
                .flatMap(index -> mapperEvidenceAuthority(index, requiredRange));
        if (mapperAuthority.isEmpty()) {
            throw new SourceSegmentNotFoundException();
        }
        if (requiredQuery.contextLines() > 0) {
            throw new IllegalArgumentException("contextLines must be 0 for mapper XML source segments");
        }
        return mapperAuthority.orElseThrow();
    }

    private Optional<SourceRange> mapperEvidenceAuthority(MapperEvidenceIndex index, SourceRange requested) {
        return java.util.stream.Stream.concat(index.statements().stream()
                .map(evidence -> evidence.location())
                .filter(authority -> contains(authority, requested)), index.fragments().stream()
                        .map(evidence -> evidence.location())
                        .filter(authority -> contains(authority, requested)))
                .min(this::mostSpecificAuthority);
    }

    private boolean contains(SourceRange authority, SourceRange requested) {
        return authority.sourceFile().equals(requested.sourceFile())
                && compare(authority.range().start(), requested.range().start()) <= 0
                && compare(requested.range().end(), authority.range().end()) <= 0;
    }

    private int compare(SyntaxPosition left, SyntaxPosition right) {
        int lineComparison = Integer.compare(left.line(), right.line());
        return lineComparison != 0
                ? lineComparison
                : Integer.compare(left.character(), right.character());
    }

    private int mostSpecificAuthority(SourceRange left, SourceRange right) {
        if (left.equals(right)) {
            return 0;
        }
        if (contains(left, right)) {
            return 1;
        }
        if (contains(right, left)) {
            return -1;
        }
        int sourceFile = left.sourceFile().compareTo(right.sourceFile());
        return sourceFile != 0 ? sourceFile : compare(left.range().start(), right.range().start());
    }
}
