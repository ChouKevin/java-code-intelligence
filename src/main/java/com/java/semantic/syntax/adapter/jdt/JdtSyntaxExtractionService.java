package com.java.semantic.syntax.adapter.jdt;

import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.java.semantic.syntax.domain.ClassMetadata;
import com.java.semantic.syntax.domain.EntryPointClass;
import com.java.semantic.syntax.domain.RepositorySyntax;
import com.java.semantic.syntax.domain.SyntaxExtractionException;
import com.java.semantic.syntax.domain.SyntaxExtractionService;

import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import lombok.extern.slf4j.Slf4j;

/** 以 JDT Core AST 實作語法層抽取 */
@Slf4j
@Service
public class JdtSyntaxExtractionService implements SyntaxExtractionService {

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
            log.warn("No Java source roots found for repository: {}", repositoryRoot);
            return RepositorySyntax.empty();
        }

        try {
            List<SourceFile> files = sourceFileScanner.scan(repositoryRoot, sourceRoots);
            MapperXmlSqlExtractor.SqlIndex sqlIndex = mapperXmlSqlExtractor.index(
                    repositoryRoot, sourceRootLocator.resourceRootsOf(repositoryRoot));

            List<EntryPointClass> entryPoints = new ArrayList<>();
            List<ClassMetadata> classes = new ArrayList<>();

            new JdtAstParser(sourceRoots).parse(files, parsed -> {
                // 逐檔隔離：一個檔案炸掉只損失該檔，不能讓整個 repo 掃出零筆
                try {
                    SourceSyntax extracted = sourceSyntaxExtractor.extractFrom(parsed, sqlIndex);
                    entryPoints.addAll(extracted.entryPoints());
                    classes.addAll(extracted.classes());
                } catch (RuntimeException e) {
                    log.warn("Failed to extract syntax from a source file, skipping it: {}",
                            parsed.source().path(), e);
                }
            });

            entryPoints.sort(Comparator.comparing(EntryPointClass::packagePath)
                    .thenComparing(EntryPointClass::className));
            classes.sort(Comparator.comparing(ClassMetadata::fullyQualifiedName));

            return new RepositorySyntax(entryPoints, classes);
        } catch (UncheckedIOException e) {
            throw new SyntaxExtractionException("Failed to extract syntax for " + repositoryRoot, e);
        }
    }
}
