package com.java.semantic.syntax.adapter.jdt;

import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.java.semantic.syntax.domain.ClassMetadata;
import com.java.semantic.syntax.domain.ClassMetadata.MethodSignature;
import com.java.semantic.syntax.domain.ClassMetadata.SqlSource;
import com.java.semantic.syntax.domain.EntryPointClass;
import com.java.semantic.syntax.domain.MapperEvidenceIndex;
import com.java.semantic.syntax.domain.MapperEvidenceRepresentation;
import com.java.semantic.syntax.domain.MapperStatementEvidence;
import com.java.semantic.syntax.domain.MapperStatementIdentity;
import com.java.semantic.syntax.domain.RepositorySyntax;
import com.java.semantic.syntax.domain.SourceExtractionOutcome;
import com.java.semantic.syntax.domain.SyntaxExtractionException;
import com.java.semantic.syntax.domain.SyntaxExtractionService;

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
            List<ClassMetadata> classes = new ArrayList<>();
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
                classes.addAll(extracted.classes());
                extractionOutcomes.add(SourceExtractionOutcome.extracted(parsed.source().repositoryRelativePath()));
            });

            entryPoints.sort(Comparator.comparing(EntryPointClass::packagePath)
                    .thenComparing(EntryPointClass::className));
            classes.sort(Comparator.comparing(ClassMetadata::fullyQualifiedName));
            MapperEvidenceIndex mapperEvidenceIndex = withAnnotationSqlEvidence(mapperExtraction.evidenceIndex(), classes);

            log.info("phase=syntax-extraction outcome=completed sourceFileCount={} classCount={} entryPointCount={}",
                    files.size(), classes.size(), entryPoints.size());
            return new RepositorySyntax(entryPoints, classes, extractionOutcomes, Optional.of(mapperEvidenceIndex));
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
            List<ClassMetadata> classes) {
        List<MapperStatementEvidence> statements = new ArrayList<>(xmlEvidenceIndex.statements());
        for (ClassMetadata metadata : classes) {
            List<MethodSignature> methods = metadata.methods();
            for (int documentOrdinal = 0; documentOrdinal < methods.size(); documentOrdinal++) {
                MethodSignature method = methods.get(documentOrdinal);
                if (method.sqlSource() != SqlSource.ANNOTATION || Objects.isNull(method.sql())) {
                    continue;
                }
                MapperStatementIdentity identity = new MapperStatementIdentity(
                        metadata.fullyQualifiedName(),
                        method.name(),
                        method.analysisTarget().target().map(target -> target.sourceFile()).orElse(metadata.sourceFile()),
                        Optional.empty(),
                        documentOrdinal,
                        MapperEvidenceRepresentation.ANNOTATION_SQL_TEXT);
                statements.add(new MapperStatementEvidence(
                        identity,
                        "annotation",
                        method.sql(),
                        List.of(),
                        method.analysisTarget().target()));
            }
        }
        return new MapperEvidenceIndex(statements, xmlEvidenceIndex.fragments());
    }
}
