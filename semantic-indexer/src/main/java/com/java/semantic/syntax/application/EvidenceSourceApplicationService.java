package com.java.semantic.syntax.application;

import com.java.semantic.repository.application.RepositoryApplicationService;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.syntax.domain.MapperEvidenceRepresentation;
import com.java.semantic.syntax.domain.MapperFragmentEvidence;
import com.java.semantic.syntax.domain.MapperStatementEvidence;
import com.java.semantic.syntax.domain.RepositorySyntax;
import com.java.semantic.syntax.domain.RevisionBoundRepositorySyntaxProvider;
import com.java.semantic.syntax.domain.RevisionPinnedSourceRangeReader;
import com.java.semantic.syntax.domain.SourceRange;
import com.java.semantic.syntax.domain.SourceRangeSegment;

import java.util.Objects;
import java.util.Optional;

/** 在既有 syntax authority 與同一 range reader 下 materialize typed evidence */
public final class EvidenceSourceApplicationService {

    private final RepositoryApplicationService repositoryApplicationService;
    private final RevisionBoundRepositorySyntaxProvider repositorySyntaxProvider;
    private final RevisionPinnedSourceRangeReader sourceRangeReader;

    public EvidenceSourceApplicationService(
            RepositoryApplicationService repositoryApplicationService,
            RevisionBoundRepositorySyntaxProvider repositorySyntaxProvider,
            RevisionPinnedSourceRangeReader sourceRangeReader) {
        this.repositoryApplicationService = Objects.requireNonNull(
                repositoryApplicationService, "repositoryApplicationService is required");
        this.repositorySyntaxProvider = Objects.requireNonNull(
                repositorySyntaxProvider, "repositorySyntaxProvider is required");
        this.sourceRangeReader = Objects.requireNonNull(sourceRangeReader, "sourceRangeReader is required");
    }

    /** 讀取 evidence 精確 range 的首個 bounded segment */
    public EvidenceSourceResult read(EvidenceSourceQuery query) {
        EvidenceSourceQuery requiredQuery = Objects.requireNonNull(query, "query is required");
        return repositoryApplicationService.withSnapshot(
                requiredQuery.repositoryId(),
                Optional.of(requiredQuery.expectedRevision()),
                snapshot -> readSnapshot(snapshot, requiredQuery));
    }

    private EvidenceSourceResult readSnapshot(RepositorySnapshot snapshot, EvidenceSourceQuery query) {
        SourceRange location = location(repositorySyntaxProvider.get(snapshot), query.identity());
        SourceRangeSegment segment = sourceRangeReader.read(snapshot, location, 0, location);
        return new EvidenceSourceResult(
                snapshot.repositoryId(), snapshot.revision(), query.identity(), location, segment);
    }

    private SourceRange location(RepositorySyntax syntax, EvidenceSourceQuery.EvidenceIdentity identity) {
        return switch (identity) {
            case EvidenceSourceQuery.AnnotationSql annotation -> statement(syntax, annotation.identity(),
                    MapperEvidenceRepresentation.ANNOTATION_SQL_TEXT).location();
            case EvidenceSourceQuery.MapperStatement statement -> statement(syntax, statement.identity(),
                    MapperEvidenceRepresentation.MAPPER_XML_ELEMENT).location();
            case EvidenceSourceQuery.MapperFragment fragment -> fragment(syntax, fragment.identity()).location();
        };
    }

    private MapperStatementEvidence statement(
            RepositorySyntax syntax,
            com.java.semantic.syntax.domain.MapperStatementIdentity identity,
            MapperEvidenceRepresentation representation) {
        if (identity.representation() != representation) {
            throw new EvidenceSourceNotFoundException();
        }
        return syntax.mapperEvidenceIndex()
                .flatMap(index -> index.statement(identity))
                .orElseThrow(EvidenceSourceNotFoundException::new);
    }

    private MapperFragmentEvidence fragment(
            RepositorySyntax syntax,
            com.java.semantic.syntax.domain.MapperFragmentIdentity identity) {
        if (identity.representation() != MapperEvidenceRepresentation.MAPPER_XML_ELEMENT) {
            throw new EvidenceSourceNotFoundException();
        }
        return syntax.mapperEvidenceIndex()
                .flatMap(index -> index.fragment(identity))
                .orElseThrow(EvidenceSourceNotFoundException::new);
    }
}
