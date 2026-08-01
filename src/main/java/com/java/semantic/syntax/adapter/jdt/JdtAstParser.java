package com.java.semantic.syntax.adapter.jdt;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.FileASTRequestor;
import org.springframework.util.CollectionUtils;

import lombok.extern.slf4j.Slf4j;

/**
 * 以 JDT Core 建立帶 binding 的 AST
 * <p>
 * 設定準則見 Task 5 Step 1：bindings 開啟、只給 sourcepath、不重建 binary classpath
 * binding 在這一層只複製 annotation 常量、receiver 宣告與 source type 證據，
 * 呼叫目標仍一律由 JDT LS 負責
 */
@Slf4j
class JdtAstParser {

    private static final String ENCODING = "UTF-8";

    private static final String COMPLIANCE = JavaCore.VERSION_21;

    private final String[] sourcepathEntries;

    private final List<Path> sourceRoots;

    JdtAstParser(List<Path> sourceRoots) {
        this.sourceRoots = sourceRoots.stream()
                .map(this::realPath)
                .distinct()
                .sorted()
                .toList();
        this.sourcepathEntries = this.sourceRoots.stream()
                .map(Path::toString)
                .toArray(String[]::new);
    }

    /** 批次解析，所有檔案共用同一組 binding 環境，跨檔案常量因此解析得出來 */
    void parse(List<SourceFile> files, Consumer<ParsedSource> consumer) {
        parseWithContext(files, consumer);
    }

    /** 批次解析並保留每個檔案實際使用的 sourcepath partition */
    JdtParseContext parseWithContext(List<SourceFile> files, Consumer<ParsedSource> consumer) {
        if (CollectionUtils.isEmpty(files)) {
            return new JdtParseContext(sourceRoots, List.of(), Set.of(), Map.of());
        }

        Set<String> collidingRootPaths = collidingRootPaths(files);
        Map<String, List<String>> sourcepathEntriesBySourceFile = new LinkedHashMap<>();
        Map<String, List<SourceFile>> filesByRoot = new LinkedHashMap<>();
        for (SourceFile file : files) {
            filesByRoot.computeIfAbsent(file.sourceRoot().toString(), ignored -> new ArrayList<>()).add(file);
        }
        List<SourceFile> filesOutsideCollidingRoots = new ArrayList<>();
        for (Map.Entry<String, List<SourceFile>> rootFiles : filesByRoot.entrySet()) {
            if (collidingRootPaths.contains(rootFiles.getKey())) {
                String[] selectedEntries = sourcepathEntriesForCollidingRoot(
                        rootFiles.getKey(), collidingRootPaths);
                rememberPartition(rootFiles.getValue(), selectedEntries, sourcepathEntriesBySourceFile);
                parseBatch(rootFiles.getValue(), selectedEntries, consumer);
            } else {
                filesOutsideCollidingRoots.addAll(rootFiles.getValue());
            }
        }
        String[] nonCollidingEntries = sourcepathEntriesExcluding(collidingRootPaths);
        rememberPartition(filesOutsideCollidingRoots, nonCollidingEntries, sourcepathEntriesBySourceFile);
        parseBatch(filesOutsideCollidingRoots, nonCollidingEntries, consumer);
        return new JdtParseContext(
                sourceRoots, files, collidingRootPaths, sourcepathEntriesBySourceFile);
    }

    /** 使用 request batch 的原始 partition 重解析唯一選定檔案 */
    ParsedSource parseSelected(SourceFile sourceFile, JdtParseContext context) {
        AtomicReference<ParsedSource> selected = new AtomicReference<>();
        List<String> sourcepath = context.sourcepathEntriesFor(sourceFile);
        parseBatch(List.of(sourceFile), sourcepath.toArray(String[]::new), selected::set);
        ParsedSource parsed = selected.get();
        if (Objects.isNull(parsed)) {
            throw new IllegalStateException("selected source was not parsed");
        }
        return parsed;
    }

    private void rememberPartition(
            List<SourceFile> files,
            String[] selectedEntries,
            Map<String, List<String>> sourcepathEntriesBySourceFile) {
        List<String> entries = List.of(selectedEntries);
        for (SourceFile file : files) {
            sourcepathEntriesBySourceFile.put(
                    file.path().toAbsolutePath().normalize().toString(), entries);
        }
    }

    private void parseBatch(List<SourceFile> files, String[] sourcepathEntries, Consumer<ParsedSource> consumer) {
        if (CollectionUtils.isEmpty(files)) {
            return;
        }
        Map<String, SourceFile> byAbsolutePath = new LinkedHashMap<>();
        for (SourceFile file : files) {
            byAbsolutePath.putIfAbsent(file.path().toAbsolutePath().normalize().toString(), file);
        }

        String[] paths = byAbsolutePath.keySet().toArray(String[]::new);
        String[] encodings = new String[paths.length];
        Arrays.fill(encodings, ENCODING);

        newParser(sourcepathEntries).createASTs(paths, encodings, new String[0], new FileASTRequestor() {
            @Override
            public void acceptAST(String sourceFilePath, CompilationUnit unit) {
                SourceFile source = byAbsolutePath.get(sourceFilePath);
                if (Objects.isNull(source)) {
                    log.warn("JDT AST ignored category={}", "UNREQUESTED_AST_SOURCE");
                    return;
                }
                consumer.accept(new ParsedSource(source, unit, readSource(source.path())));
            }
        }, null);
    }

    private Set<String> collidingRootPaths(List<SourceFile> files) {
        Map<String, Set<String>> rootPathsByFullyQualifiedName = new LinkedHashMap<>();
        for (SourceFile file : files) {
            for (String fullyQualifiedName : topLevelFullyQualifiedNames(file)) {
                rootPathsByFullyQualifiedName.computeIfAbsent(fullyQualifiedName, ignored -> new HashSet<>())
                        .add(file.sourceRoot().toString());
            }
        }
        Set<String> roots = new HashSet<>();
        for (Set<String> rootPaths : rootPathsByFullyQualifiedName.values()) {
            if (rootPaths.size() > 1) {
                roots.addAll(rootPaths);
            }
        }
        return Set.copyOf(roots);
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

    private String[] sourcepathEntriesExcluding(Set<String> excludedRootPaths) {
        return Arrays.stream(sourcepathEntries)
                .filter(entry -> !excludedRootPaths.contains(entry))
                .toArray(String[]::new);
    }

    private String[] sourcepathEntriesForCollidingRoot(String rootPath, Set<String> collidingRootPaths) {
        return Arrays.stream(sourcepathEntries)
                .filter(entry -> entry.equals(rootPath) || !collidingRootPaths.contains(entry))
                .toArray(String[]::new);
    }

    private String readSource(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read parsed source " + path, e);
        }
    }

    private Path realPath(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to resolve parser source root " + path, e);
        }
    }

    private ASTParser newParser(String[] sourcepathEntries) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(true);
        parser.setStatementsRecovery(true);

        Map<String, String> options = JavaCore.getOptions();
        JavaCore.setComplianceOptions(COMPLIANCE, options);
        parser.setCompilerOptions(options);

        String[] rootEncodings = new String[sourcepathEntries.length];
        Arrays.fill(rootEncodings, ENCODING);

        // classpathEntries 刻意留空：專案的 jar 模型屬於 JDT LS，兩份模型會漂移
        parser.setEnvironment(new String[0], sourcepathEntries, rootEncodings, true);
        return parser;
    }
}
