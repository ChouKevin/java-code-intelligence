package com.java.semantic.syntax.adapter.jdt;

import com.java.semantic.syntax.domain.ExactSourceDeclaration;
import com.java.semantic.syntax.domain.ExactSourceDeclarationResolver;
import com.java.semantic.syntax.domain.ExactSourceDeclarationTarget;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 以 repository-wide partition 與單檔 binding parse 定位 exact declaration */
@Service
public final class JdtExactSourceDeclarationResolver implements ExactSourceDeclarationResolver {

    private final SourceRootLocator sourceRootLocator = new SourceRootLocator();
    private final SourceFileScanner sourceFileScanner = new SourceFileScanner();
    private final JdtSourcepathPartitionResolver partitionResolver = new JdtSourcepathPartitionResolver();
    private final JdtSourceDeclarationLocator declarationLocator = new JdtSourceDeclarationLocator();

    @Override
    public Optional<ExactSourceDeclaration> resolve(Path repositoryRoot, ExactSourceDeclarationTarget target) {
        Objects.requireNonNull(repositoryRoot, "repositoryRoot is required");
        Objects.requireNonNull(target, "target is required");
        List<Path> sourceRoots = sourceRootLocator.sourceRootsOf(repositoryRoot);
        if (sourceRoots.isEmpty()) {
            return Optional.empty();
        }
        List<SourceFile> files = sourceFileScanner.scan(repositoryRoot, sourceRoots);
        Optional<SourceFile> selected = files.stream()
                .filter(file -> target.sourceFile().equals(file.repositoryRelativePath()))
                .findFirst();
        if (selected.isEmpty()) {
            return Optional.empty();
        }
        JdtParseContext context = partitionResolver.resolve(sourceRoots, files);
        JdtAstParser parser = new JdtAstParser(sourceRoots);
        ParsedSource parsed = parser.parseSelected(selected.orElseThrow(), context);
        return declarationLocator.exact(parsed, target)
                .map(declaration -> new ExactSourceDeclaration(
                        target,
                        declarationLocator.range(parsed, declaration.rangeNode()),
                        declarationLocator.range(parsed, declaration.name())));
    }
}
