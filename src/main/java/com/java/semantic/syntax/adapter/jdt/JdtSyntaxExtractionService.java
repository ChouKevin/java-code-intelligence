package com.java.semantic.syntax.adapter.jdt;

import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.java.semantic.syntax.domain.EntryPointClass;
import com.java.semantic.syntax.domain.MapperEvidenceIndex;
import com.java.semantic.syntax.domain.MapperEvidenceRepresentation;
import com.java.semantic.syntax.domain.MapperStatementEvidence;
import com.java.semantic.syntax.domain.MapperStatementIdentity;
import com.java.semantic.syntax.domain.MapperStatementKey;
import com.java.semantic.syntax.domain.RepositorySyntax;
import com.java.semantic.syntax.domain.SourceExtractionOutcome;
import com.java.semantic.syntax.domain.SyntaxExtractionException;
import com.java.semantic.syntax.domain.SyntaxExtractionService;
import com.java.semantic.syntax.domain.SourceMethodMetadata;
import com.java.semantic.syntax.domain.SourceTypeMetadata;
import com.java.semantic.syntax.domain.SqlSourceKind;

import org.eclipse.jdt.core.compiler.IProblem;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import lombok.extern.slf4j.Slf4j;

/** 以 JDT Core AST 實作語法層抽取 */
@Slf4j
@Service
public class JdtSyntaxExtractionService implements SyntaxExtractionService {

    private static final String JDT_SYNTAX_PROBLEM = "JDT_SYNTAX_PROBLEM";

    private final SourceRootLocator sourceRootLocator = new SourceRootLocator();

    private final SourceFileScanner sourceFileScanner = new SourceFileScanner();

    private final MapperXmlSqlExtractor mapperXmlSqlExtractor = new MapperXmlSqlExtractor();

    private final SourceSyntaxExtractor sourceSyntaxExtractor;

    public JdtSyntaxExtractionService() {
        this(new SourceSyntaxExtractor());
    }

    JdtSyntaxExtractionService(SourceSyntaxExtractor sourceSyntaxExtractor) {
        this.sourceSyntaxExtractor = sourceSyntaxExtractor;
    }

    @Override
    public RepositorySyntax extract(Path repositoryRoot) {
        List<Path> sourceRoots = sourceRootLocator.sourceRootsOf(repositoryRoot);
        if (CollectionUtils.isEmpty(sourceRoots)) {
            log.warn("Syntax extraction skipped category={}", "JAVA_SOURCE_ROOTS_NOT_FOUND");
            return RepositorySyntax.empty();
        }

        try {
            List<SourceFile> files = sourceFileScanner.scan(repositoryRoot, sourceRoots);
            log.info("phase=syntax-extraction outcome=started sourceFileCount={}", files.size());
            MapperXmlSqlExtractor.Extraction mapperExtraction = mapperXmlSqlExtractor.extract(
                    repositoryRoot, sourceRootLocator.resourceRootsOf(repositoryRoot));

            List<EntryPointClass> entryPoints = new ArrayList<>();
            List<SourceTypeMetadata> sourceTypes = new ArrayList<>();
            List<SourceExtractionOutcome> extractionOutcomes = new ArrayList<>();

            new JdtAstParser(sourceRoots).parse(files, parsed -> {
                if (hasSyntaxFailure(parsed)) {
                    extractionOutcomes.add(SourceExtractionOutcome.syntaxFailed(
                            parsed.source().repositoryRelativePath(), JDT_SYNTAX_PROBLEM));
                    log.warn("Syntax extraction skipped source category={}", JDT_SYNTAX_PROBLEM);
                    return;
                }
                SourceSyntax extracted = sourceSyntaxExtractor.extractFrom(parsed, mapperExtraction.sqlIndex());
                entryPoints.addAll(extracted.entryPoints());
                sourceTypes.addAll(extracted.sourceTypes());
                extractionOutcomes.add(SourceExtractionOutcome.extracted(parsed.source().repositoryRelativePath()));
            });

            entryPoints.sort(Comparator.comparing((EntryPointClass entryPoint) -> entryPoint.sourceType().sourceFile())
                    .thenComparing(entryPoint -> entryPoint.sourceType().javaType().fullyQualifiedName()));
            sourceTypes.sort(Comparator.comparing(metadata -> metadata.declaration().identity().fullyQualifiedName()));
            MapperEvidenceIndex mapperEvidenceIndex = withAnnotationSqlEvidence(
                    mapperExtraction.evidenceIndex(), sourceTypes);

            log.info("phase=syntax-extraction outcome=completed sourceFileCount={} classCount={} entryPointCount={}",
                    files.size(), sourceTypes.size(), entryPoints.size());
            return new RepositorySyntax(entryPoints, sourceTypes, extractionOutcomes, Optional.of(mapperEvidenceIndex));
        } catch (UncheckedIOException exception) {
            log.warn("Syntax extraction failed category={} exceptionType={}",
                    "REPOSITORY_SYNTAX_EXTRACTION_FAILED", exception.getClass().getSimpleName());
            throw new SyntaxExtractionException("Repository syntax extraction failed");
        }
    }

    private boolean hasSyntaxFailure(ParsedSource parsed) {
        return List.of(parsed.unit().getProblems()).stream()
                .anyMatch(problem -> problem.isError() && (problem.getID() & IProblem.Syntax) != 0);
    }

    private MapperEvidenceIndex withAnnotationSqlEvidence(
            MapperEvidenceIndex xmlEvidenceIndex,
            List<SourceTypeMetadata> sourceTypes) {
        List<MapperStatementEvidence> statements = new ArrayList<>(xmlEvidenceIndex.statements());
        for (SourceTypeMetadata metadata : sourceTypes) {
            List<SourceMethodMetadata> methods = metadata.members().methods();
            for (int documentOrdinal = 0; documentOrdinal < methods.size(); documentOrdinal++) {
                SourceMethodMetadata method = methods.get(documentOrdinal);
                if (method.sqlSource() != SqlSourceKind.ANNOTATION || method.annotationSqlLocation().isEmpty()) {
                    continue;
                }
                MapperStatementIdentity identity = new MapperStatementIdentity(
                        new MapperStatementKey(
                                metadata.declaration().identity().fullyQualifiedName(),
                                method.name()),
                        method.declarationLocation().sourceFile(),
                        Optional.empty(),
                        documentOrdinal,
                        MapperEvidenceRepresentation.ANNOTATION_SQL_TEXT);
                statements.add(new MapperStatementEvidence(
                        identity,
                        "annotation",
                        method.annotationSqlLocation().orElseThrow(),
                        List.of(),
                        method.analysisTarget().target()));
            }
        }
        return new MapperEvidenceIndex(statements, xmlEvidenceIndex.fragments());
    }
}
