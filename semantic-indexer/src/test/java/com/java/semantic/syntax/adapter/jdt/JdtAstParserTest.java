package com.java.semantic.syntax.adapter.jdt;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Step 1 的驗收：bindings 開啟且 sourcepath 正確設定時，跨檔案原始碼常量必須解析得出來，
 * 編譯期字串串接也必須被摺疊
 * <p>
 * 這兩件事只有 setResolveBindings(true) 與 setEnvironment(...) 同時成立才會發生
 */
class JdtAstParserTest {

    private static final Path FIXTURE = Path.of("src/test/resources/fixtures/syntax-extraction");

    @Test
    void should_resolve_a_cross_file_source_constant_when_bindings_and_sourcepath_are_configured() {
        String value = firstMappingValueOf("get");

        assertThat(value).isEqualTo("/{id}");
    }

    @Test
    void should_fold_a_compile_time_concatenation_when_a_constant_is_built_from_two_literals() {
        String value = firstMappingValueOf("folded");

        assertThat(value)
                .as("RouteConstants.FOLDED 是 \"/a\" + \"/b\"，JDT 應摺疊為 /a/b 而非回傳未解析的運算式")
                .isEqualTo("/a/b");
    }

    @Test
    void should_resolve_a_class_level_constant_when_the_constant_lives_in_another_file() {
        List<ParsedSource> sources = parseFixture();
        AbstractTypeDeclaration controller = sources.stream()
                .flatMap(source -> SourceTypes.allTypesOf(source.unit()).stream())
                .filter(type -> "OrderApiController".equals(type.getName().getIdentifier()))
                .findFirst()
                .orElseThrow();

        Annotation mapping = AnnotationReader.find(controller, "RequestMapping").orElseThrow();

        assertThat(AnnotationReader.stringValues(mapping, "value", "path"))
                .as("@RequestMapping(RouteConstants.BASE) 必須解析為 /const")
                .containsExactly("/const");
    }

    private String firstMappingValueOf(String methodName) {
        List<ParsedSource> sources = parseFixture();
        for (ParsedSource source : sources) {
            for (AbstractTypeDeclaration type : SourceTypes.allTypesOf(source.unit())) {
                for (MethodDeclaration method : SourceTypes.declaredMethodsOf(type)) {
                    if (!methodName.equals(method.getName().getIdentifier())) {
                        continue;
                    }
                    Annotation mapping = AnnotationReader.findAny(method, List.of("GetMapping", "RequestMapping"))
                            .orElseThrow();
                    return AnnotationReader.stringValues(mapping, "value", "path").get(0);
                }
            }
        }
        throw new IllegalStateException("fixture method not found: " + methodName);
    }

    private List<ParsedSource> parseFixture() {
        List<Path> sourceRoots = List.of(FIXTURE.resolve("src/main/java"));
        List<SourceFile> files = new SourceFileScanner().scan(FIXTURE, sourceRoots);

        List<ParsedSource> parsed = new ArrayList<>();
        new JdtAstParser(sourceRoots).parse(files, parsed::add);
        return parsed;
    }
}
