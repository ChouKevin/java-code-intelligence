package com.java.semantic.syntax.adapter.jdt;

import java.nio.file.Path;
import java.util.Objects;

import com.java.semantic.syntax.domain.RepositorySyntax;

/** 測試共用的 fixture 抽取，結果快取避免每個測試類別都重跑一次 JDT 批次解析 */
final class SyntaxFixtures {

    static final Path SYNTAX_EXTRACTION = Path.of("src/test/resources/fixtures/syntax-extraction");

    static final Path MULTI_MODULE = Path.of("src/test/resources/fixtures/multi-module-data-access");

    static final Path SPRING_BASIC = Path.of("src/test/resources/fixtures/spring-basic");

    private static RepositorySyntax syntaxExtraction;

    private static RepositorySyntax multiModule;

    private SyntaxFixtures() {
    }

    static synchronized RepositorySyntax extractSyntaxFixture() {
        if (Objects.isNull(syntaxExtraction)) {
            syntaxExtraction = extract(SYNTAX_EXTRACTION);
        }
        return syntaxExtraction;
    }

    static synchronized RepositorySyntax extractMultiModuleFixture() {
        if (Objects.isNull(multiModule)) {
            multiModule = extract(MULTI_MODULE);
        }
        return multiModule;
    }

    static RepositorySyntax extract(Path repositoryRoot) {
        return new JdtSyntaxExtractionService().extract(repositoryRoot);
    }
}
