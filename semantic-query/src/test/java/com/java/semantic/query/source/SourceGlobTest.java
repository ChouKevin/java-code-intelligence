package com.java.semantic.query.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.java.semantic.query.source.SourceQueryException.Code;
import java.util.List;
import org.junit.jupiter.api.Test;

class SourceGlobTest {
    @Test
    void root_literals_bare_wildcards_and_zero_directory_globstars_select_exact_sets() {
        List<String> paths = List.of("A.java", "src/A.java", "src/deep/B.java", "pom.xml", "src/pom.xml", "src", "README.md");
        selection(paths, "*.java", "A.java", "src/A.java", "src/deep/B.java");
        selection(paths, "**/*.java", "A.java", "src/A.java", "src/deep/B.java");
        selection(paths, "src/**/*.java", "src/A.java", "src/deep/B.java");
        selection(paths, "src/*.java", "src/A.java");
        selection(paths, "pom.xml", "pom.xml");
        selection(paths, "**/pom.xml", "pom.xml", "src/pom.xml");
        selection(paths, "src/**", "src/A.java", "src/deep/B.java", "src/pom.xml");
        selection(paths, "{pom.xml,README.md}", "pom.xml", "README.md");
        selection(paths, "{*.java,src/pom.xml}", "A.java", "src/A.java", "src/deep/B.java", "src/pom.xml");
    }

    @Test
    void unicode_scalars_classes_and_escaped_literals_are_not_regex_or_native_byte_semantics() {
        List<String> paths = List.of("A.java", "é.java", "😀.java", "ab.java", "a?.java", "[x].java",
                "!bang.java", "a,b.java", "src/😀.java", "^.java", "-.java", "semi; colon.java");
        selection(paths, "?.java", "A.java", "é.java", "😀.java", "src/😀.java", "^.java", "-.java");
        selection(paths, "[é-😀].java", "é.java", "😀.java", "src/😀.java");
        selection(paths, "[!A-é].java", "😀.java", "src/😀.java", "-.java");
        selection(paths, "a\\?.java", "a?.java");
        selection(paths, "\\[x\\].java", "[x].java");
        selection(paths, "\\!bang.java", "!bang.java");
        selection(paths, "{a\\,b.java,{é,😀}.java}", "a,b.java", "é.java", "😀.java");
        selection(paths, "[^-].java", "^.java", "-.java");
        List<String> metacharacters = List.of("!.java", "-.java", "[.java", "].java", "*.java", "{x}.java");
        selection(metacharacters, "[\\!].java", "!.java");
        selection(metacharacters, "[\\-].java", "-.java");
        selection(metacharacters, "[\\[\\]].java", "[.java", "].java");
        selection(metacharacters, "\\*.java", "*.java");
        selection(metacharacters, "\\{x\\}.java", "{x}.java");
        selection(paths, "semi; colon.java", "semi; colon.java");
    }

    @Test
    void escaped_hyphen_can_start_a_scalar_range() {
        List<String> paths = List.of("-.range", "..range", "0.range", "1.range", ".range");
        selection(paths, "[\\--0].range", "-.range", "..range", "0.range");
        selection(paths, "[--0].range", "-.range", "0.range");
        invalid("[0-\\-].range");
    }

    @Test
    void unsupported_or_malformed_patterns_fail_as_invalid_arguments() {
        for (String pattern : List.of("{a,b", "[abc", "{}", "{a}", "{,a}", "{a,}", "{src,../src}/*.java",
                "/a", "https://host/a", "C:/a", "a//b", "./a", "a/../b", "a/", "!*.java", "a**b",
                "***", "**x/a", "[z-a]", "[a/]", "[[:alpha:]]", "{1..3}", "@(a|b)", "*(a|b)",
                "?(a|b)", "+(a|b)", "!(a|b)", "a\\", "a\\q", "a\\\\b", "\uD800", "a\u0000b", "a\u007fb")) {
            invalid(pattern);
        }
    }

    @Test
    void all_compiler_limits_accept_the_boundary_and_reject_the_next_value() {
        selection(List.of("😀".repeat(256)), "😀".repeat(256), "😀".repeat(256));
        invalid("😀".repeat(257));
        selection(List.of("a", "b", "c", "d", "e"), "{a,{b,{c,{d,e}}}}", "a", "b", "c", "d", "e");
        invalid("{a,{b,{c,{d,{e,f}}}}}");
        String sixtyFour = "{a,b}".repeat(6);
        selection(List.of("aaaaaa", "bbbbbb"), sixtyFour, "aaaaaa", "bbbbbb");
        invalid("{" + String.join(",", java.util.Collections.nCopies(65, "a")) + "}");
        String stateBoundary = sixtyFour + "x".repeat(121);
        selection(List.of("aaaaaa" + "x".repeat(121)), stateBoundary, "aaaaaa" + "x".repeat(121));
        invalid(sixtyFour + "x".repeat(122));
        String projectionBoundary = sixtyFour + "?".repeat(67);
        selection(List.of("aaaaaa" + "😀".repeat(67)), projectionBoundary, "aaaaaa" + "😀".repeat(67));
        invalid(sixtyFour + "?".repeat(68));
    }

    @Test
    void matching_checks_the_request_deadline_and_reuses_scratch_without_previous_path_state() {
        SourceGlob compiled = SourceGlob.compile("src/**/*.java");
        SourceGlob.Matcher matcher = compiled.matcher(Long.MAX_VALUE);
        assertThat(matcher.matches("src/A.java")).isTrue();
        assertThat(matcher.matches("A.java")).isFalse();
        assertThat(matcher.matches("src/deep/B.java")).isTrue();
        assertThatThrownBy(() -> compiled.matcher(System.nanoTime() - 1).matches("src/A.java"))
                .isInstanceOfSatisfying(SourceQueryException.class, exception -> assertThat(exception.code()).isEqualTo(Code.SOURCE_TIMEOUT));
    }

    private static void selection(List<String> paths, String pattern, String... expected) {
        SourceGlob.Matcher matcher = SourceGlob.compile(pattern).matcher(Long.MAX_VALUE);
        assertThat(paths.stream().filter(matcher::matches).toList()).as(pattern).containsExactlyInAnyOrder(expected);
    }

    private static void invalid(String pattern) {
        assertThatThrownBy(() -> SourceGlob.compile(pattern)).as(pattern)
                .isInstanceOfSatisfying(SourceQueryException.class, exception -> assertThat(exception.code()).isEqualTo(Code.INVALID_ARGUMENT));
    }
}
