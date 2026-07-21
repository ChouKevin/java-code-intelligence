package com.java.semantic.syntax.adapter.jdt;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

import org.eclipse.jdt.core.JavaCore;
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

    JdtAstParser(List<Path> sourceRoots) {
        this.sourcepathEntries = sourceRoots.stream()
                .map(root -> root.toAbsolutePath().normalize().toString())
                .toArray(String[]::new);
    }

    /** 批次解析，所有檔案共用同一組 binding 環境，跨檔案常量因此解析得出來 */
    void parse(List<SourceFile> files, Consumer<ParsedSource> consumer) {
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

        newParser().createASTs(paths, encodings, new String[0], new FileASTRequestor() {
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

    private String readSource(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read parsed source " + path, e);
        }
    }

    private ASTParser newParser() {
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
