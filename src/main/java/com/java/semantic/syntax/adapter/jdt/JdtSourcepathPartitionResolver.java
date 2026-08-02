package com.java.semantic.syntax.adapter.jdt;

import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 以 bindings-free declaration scan 計算 duplicate-FQN sourcepath partition */
final class JdtSourcepathPartitionResolver {

    JdtParseContext resolve(List<Path> sourceRoots, List<SourceFile> files) {
        List<Path> realRoots = sourceRoots.stream()
                .map(this::realPath)
                .distinct()
                .sorted()
                .toList();
        if (files.isEmpty()) {
            return new JdtParseContext(realRoots, List.of(), Set.of(), Map.of());
        }
        String[] sourcepathEntries = realRoots.stream().map(Path::toString).toArray(String[]::new);
        Set<String> collidingRootPaths = collidingRootPaths(files);
        Map<String, List<String>> entriesBySourceFile = new LinkedHashMap<>();
        for (SourceFile file : files) {
            String rootPath = file.sourceRoot().toString();
            List<String> selected = Arrays.stream(sourcepathEntries)
                    .filter(entry -> !collidingRootPaths.contains(entry) || entry.equals(rootPath))
                    .toList();
            entriesBySourceFile.put(file.path().toAbsolutePath().normalize().toString(), selected);
        }
        return new JdtParseContext(realRoots, files, collidingRootPaths, entriesBySourceFile);
    }

    private Set<String> collidingRootPaths(List<SourceFile> files) {
        Map<String, Set<String>> rootsByFullyQualifiedName = new LinkedHashMap<>();
        for (SourceFile file : files) {
            for (String fullyQualifiedName : topLevelFullyQualifiedNames(file)) {
                rootsByFullyQualifiedName.computeIfAbsent(fullyQualifiedName, ignored -> new HashSet<>())
                        .add(file.sourceRoot().toString());
            }
        }
        Set<String> collidingRoots = new HashSet<>();
        for (Set<String> roots : rootsByFullyQualifiedName.values()) {
            if (roots.size() > 1) {
                collidingRoots.addAll(roots);
            }
        }
        return Set.copyOf(collidingRoots);
    }

    private Set<String> topLevelFullyQualifiedNames(SourceFile file) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setSource(readSource(file.path()).toCharArray());
        CompilationUnit unit = (CompilationUnit) parser.createAST(null);
        String packageName = Objects.nonNull(unit.getPackage())
                ? unit.getPackage().getName().getFullyQualifiedName()
                : "";
        Set<String> fullyQualifiedNames = new HashSet<>();
        for (Object declaration : unit.types()) {
            if (declaration instanceof AbstractTypeDeclaration type) {
                String simpleName = type.getName().getIdentifier();
                fullyQualifiedNames.add(packageName.isEmpty() ? simpleName : packageName + "." + simpleName);
            }
        }
        return Set.copyOf(fullyQualifiedNames);
    }

    private String readSource(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to scan source declaration " + path, exception);
        }
    }

    private Path realPath(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to resolve source root " + path, exception);
        }
    }
}
