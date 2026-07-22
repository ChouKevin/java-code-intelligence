package com.java.semantic.identity;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.java.semantic.syntax.domain.AnalysisTargetStatus;
import com.java.semantic.syntax.domain.MethodTargetResolution;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class MethodTargetTest {

    @Test
    void should_preserve_repository_relative_source_and_parameter_list_independently_from_callers() {
        List<String> parameterTypes = new ArrayList<>(List.of("java.lang.String"));

        MethodTarget target = new MethodTarget(
                "module-a/src/main/java/com/example/Order.java",
                "com.example", "Order", "submit", parameterTypes);
        parameterTypes.add("int");

        assertThat(target.sourceFile()).isEqualTo("module-a/src/main/java/com/example/Order.java");
        assertThat(target.parameterTypes()).containsExactly("java.lang.String");
        assertThatThrownBy(() -> target.parameterTypes().add("long"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void should_reject_non_repository_relative_source_forms() {
        List<String> invalidPaths = List.of("", " ", "/src/Main.java", "src\\Main.java", "C:/Main.java",
                "https://example.test/Main.java", "src/../Main.java", "src/./Main.java", "src /Main.java",
                "src/\u00A0Main.java", "src/\u2007Main.java", "src/\u202FMain.java");

        for (String invalidPath : invalidPaths) {
            assertThatIllegalArgumentException().isThrownBy(() -> RepositoryRelativeSource.requireValid(invalidPath));
        }
    }

    @Test
    void should_expose_repository_relative_source_as_a_non_instantiable_validation_utility() {
        assertThat(Modifier.isFinal(RepositoryRelativeSource.class.getModifiers())).isTrue();
        assertThat(RepositoryRelativeSource.class.getConstructors()).isEmpty();
        assertThat(RepositoryRelativeSource.class.getDeclaredConstructors()).singleElement()
                .satisfies(constructor -> assertThat(Modifier.isPrivate(constructor.getModifiers())).isTrue());
        assertThat(RepositoryRelativeSource.class.getDeclaredMethods())
                .anySatisfy(method -> {
                    assertThat(method.getName()).isEqualTo("requireValid");
                    assertThat(Modifier.isPublic(method.getModifiers())).isTrue();
                    assertThat(Modifier.isStatic(method.getModifiers())).isTrue();
                    assertThat(method.getReturnType()).isEqualTo(String.class);
                    assertThat(method.getParameterTypes()).containsExactly(String.class);
                });
    }

    @Test
    void should_fail_fast_when_utility_constructor_is_invoked_reflectively() throws Exception {
        Constructor<RepositoryRelativeSource> constructor = RepositoryRelativeSource.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        assertThatThrownBy(constructor::newInstance)
                .isInstanceOf(InvocationTargetException.class)
                .hasCauseInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void should_allow_default_package_but_reject_blank_method_target_members() {
        assertThat(new MethodTarget("src/Main.java", "", "Main", "run", List.of())
                .packageName()).isEmpty();

        assertThatIllegalArgumentException().isThrownBy(() -> new MethodTarget(
                "src/Main.java", "com.example", " ", "run", List.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> new MethodTarget(
                "src/Main.java", "com.example", "Main", " ", List.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> new MethodTarget(
                "src/Main.java", "com.example", "Main", "run", List.of(" ")));
    }

    @Test
    void should_accept_java_identifiers_for_nested_and_unicode_method_targets() {
        MethodTarget target = new MethodTarget(
                "src/Outer.java", "com.example", "Outer.Inner", "方法", List.of());

        assertThat(target.className()).isEqualTo("Outer.Inner");
        assertThat(target.methodName()).isEqualTo("方法");
    }

    @Test
    void should_reject_oversized_or_non_identifier_method_target_members() {
        assertThatIllegalArgumentException().isThrownBy(() -> new MethodTarget(
                "a".repeat(1025), "com.example", "Main", "run", List.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> new MethodTarget(
                "src/Main.java", "com.example", "Main\nForged", "run", List.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> new MethodTarget(
                "src/Main.java", "com.example", "Main", "run\rForged", List.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> new MethodTarget(
                "src/Main.java", "com.example", "Main\u0000Forged", "run", List.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> new MethodTarget(
                "src/Main.java", "com.example", "not-a-class", "run", List.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> new MethodTarget(
                "src/Main.java", "com.example", "Main", "1run", List.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> new MethodTarget(
                "src/Main.java", "com.example", "Main", "m".repeat(256), List.of()));
    }

    @Test
    void should_defensively_copy_resolution_candidates_and_enforce_status_invariants() {
        MethodTarget first = new MethodTarget(
                "module-a/src/Main.java", "example", "Main", "run", List.of());
        MethodTarget second = new MethodTarget(
                "module-b/src/Main.java", "example", "Main", "run", List.of());
        List<MethodTarget> candidates = new ArrayList<>(List.of(first, second));

        MethodTargetResolution resolution = new MethodTargetResolution(
                AnalysisTargetStatus.AMBIGUOUS, Optional.empty(), candidates, "DUPLICATE_DECLARATION");
        candidates.clear();

        assertThat(resolution.candidates()).containsExactly(first, second);
        assertThatThrownBy(() -> resolution.candidates().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatIllegalArgumentException().isThrownBy(() -> new MethodTargetResolution(
                AnalysisTargetStatus.RESOLVED, Optional.empty(), List.of(), ""));
        assertThatIllegalArgumentException().isThrownBy(() -> new MethodTargetResolution(
                AnalysisTargetStatus.UNRESOLVED, Optional.empty(), List.of(), " "));
    }

    @Test
    void should_reject_ambiguous_resolution_when_candidates_repeat_the_same_target() {
        MethodTarget target = new MethodTarget(
                "module-a/src/Main.java", "example", "Main", "run", List.of());

        assertThatIllegalArgumentException().isThrownBy(() -> new MethodTargetResolution(
                AnalysisTargetStatus.AMBIGUOUS, Optional.empty(), List.of(target, target), "DUPLICATE_DECLARATION"));
    }

    @Test
    void should_reject_ambiguous_resolution_when_any_candidate_is_duplicated() {
        MethodTarget first = new MethodTarget(
                "module-a/src/Main.java", "example", "Main", "run", List.of());
        MethodTarget second = new MethodTarget(
                "module-b/src/Main.java", "example", "Main", "run", List.of());

        assertThatIllegalArgumentException().isThrownBy(() -> new MethodTargetResolution(
                AnalysisTargetStatus.AMBIGUOUS, Optional.empty(), List.of(first, second, first),
                "DUPLICATE_DECLARATION"));
    }
}
