package com.java.semantic.indexer.incremental;

import com.java.semantic.model.index.SymbolDocument;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;

class SourceContractChangeDetectorTest {

    private final SourceContractChangeDetector detector = SourceContractChangeDetector.lightweight();

    @ParameterizedTest(name = "{0}")
    @MethodSource("frameworkAnnotationChanges")
    void should_report_framework_discovery_impact_when_indexed_annotation_contract_changes(
            String name, String oldSource, String newSource) {
        SourceContractChangeDetector.Impact impact = detector.detect(
                ChangedSource.modify("api/Annotated.java", oldSource, newSource), List.<SymbolDocument>of());

        assertThat(impact.frameworkAnnotation()).isTrue();
        assertThat(impact.publicDeclaration()).isTrue();
        assertThat(impact.uncertain()).isFalse();
    }

    @Test
    void should_be_uncertain_when_framework_annotation_syntax_is_incomplete() {
        SourceContractChangeDetector.Impact impact = detector.detect(ChangedSource.modify("api/Annotated.java",
                "@GetMapping(\"/orders\") void get() {}", "@GetMapping(\"/orders\" void get() {}"),
                List.<SymbolDocument>of());

        assertThat(impact.uncertain()).isTrue();
    }

    private static Stream<Arguments> frameworkAnnotationChanges() {
        return Stream.of(
                Arguments.of("rest controller", "@RestController class Orders {}", "class Orders {}"),
                Arguments.of("rabbit listener", "@RabbitListener(queues = \"orders\") void receive() {}",
                        "@RabbitListener(queues = \"payments\") void receive() {}"),
                Arguments.of("feign client", "@FeignClient(name = \"catalog\") interface Client {}",
                        "@FeignClient(name = \"inventory\") interface Client {}"),
                Arguments.of("get mapping value", "@GetMapping(\"/orders\") void get() {}",
                        "@GetMapping(\"/payments\") void get() {}"),
                Arguments.of("get mapping value with comments", """
                        @GetMapping /* before arguments */
                        (value = \"/orders\" /* parentheses: ) ( */)
                        void get() {}
                        """, """
                        @GetMapping // before arguments
                        (value = \"/payments\" /* parentheses: ) ( */)
                        void get() {}
                        """));
    }
}
