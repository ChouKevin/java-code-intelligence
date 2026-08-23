package com.java.semantic.syntax.domain;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.java.semantic.model.codefact.JavaTypeIdentity;
import com.java.semantic.model.codefact.MethodTarget;
import com.java.semantic.model.codefact.RepositoryRelativeSource;
import com.java.semantic.model.codefact.SourceTypeIdentity;
import com.java.semantic.syntax.domain.AnalysisTargetStatus;
import com.java.semantic.syntax.domain.MethodTargetResolution;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class MethodTargetTest {

    @Test
    void should_compose_java_type_and_source_identity_with_derived_method_projections() {
        List<String> parameterTypes = new ArrayList<>(List.of("java.lang.String"));

        MethodTarget target = new MethodTarget(
                new SourceTypeIdentity(
                        new JavaTypeIdentity("com.example", "Order"),
                        "module-a/src/main/java/com/example/Order.java"),
                "submit", parameterTypes);
        parameterTypes.add("int");

        assertThat(target.sourceFile()).isEqualTo("module-a/src/main/java/com/example/Order.java");
        assertThat(target.packageName()).isEqualTo("com.example");
        assertThat(target.className()).isEqualTo("Order");
        assertThat(target.fullyQualifiedClassName()).isEqualTo("com.example.Order");
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
            assertThatIllegalArgumentException().isThrownBy(() -> new SourceTypeIdentity(
                    new JavaTypeIdentity("com.example", "Main"), invalidPath));
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
        assertThat(new MethodTarget(
                new SourceTypeIdentity(
                        new JavaTypeIdentity("", "Main"),
                        "src/Main.java"),
                "run",
                List.of())
                .packageName()).isEmpty();

        assertThatIllegalArgumentException().isThrownBy(() -> new MethodTarget(
                new SourceTypeIdentity(
                        new JavaTypeIdentity("com.example", " "),
                        "src/Main.java"),
                "run",
                List.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> new MethodTarget(
                new SourceTypeIdentity(
                        new JavaTypeIdentity("com.example", "Main"),
                        "src/Main.java"),
                " ",
                List.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> new MethodTarget(
                new SourceTypeIdentity(
                        new JavaTypeIdentity("com.example", "Main"),
                        "src/Main.java"),
                "run",
                List.of(" ")));
    }

    @Test
    void should_accept_java_identifiers_for_nested_and_unicode_method_targets() {
        MethodTarget target = new MethodTarget(
                new SourceTypeIdentity(
                        new JavaTypeIdentity("com.example", "Outer.Inner"),
                        "src/Outer.java"),
                "方法",
                List.of());

        assertThat(target.className()).isEqualTo("Outer.Inner");
        assertThat(target.methodName()).isEqualTo("方法");
    }

    @Test
    void should_reject_oversized_or_non_identifier_method_target_members() {
        assertThatIllegalArgumentException().isThrownBy(() -> new MethodTarget(
                new SourceTypeIdentity(
                        new JavaTypeIdentity("com.example", "Main"),
                        "a".repeat(1025)),
                "run",
                List.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> new MethodTarget(
                new SourceTypeIdentity(
                        new JavaTypeIdentity("com.example", "Main\nForged"),
                        "src/Main.java"),
                "run",
                List.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> new MethodTarget(
                new SourceTypeIdentity(
                        new JavaTypeIdentity("com.example", "Main"),
                        "src/Main.java"),
                "run\rForged",
                List.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> new MethodTarget(
                new SourceTypeIdentity(
                        new JavaTypeIdentity("com.example", "Main\u0000Forged"),
                        "src/Main.java"),
                "run",
                List.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> new MethodTarget(
                new SourceTypeIdentity(
                        new JavaTypeIdentity("com.example", "not-a-class"),
                        "src/Main.java"),
                "run",
                List.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> new MethodTarget(
                new SourceTypeIdentity(
                        new JavaTypeIdentity("com.example", "Main"),
                        "src/Main.java"),
                "1run",
                List.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> new MethodTarget(
                new SourceTypeIdentity(
                        new JavaTypeIdentity("com.example", "Main"),
                        "src/Main.java"),
                "m".repeat(256),
                List.of()));
    }

    @Test
    void should_defensively_copy_resolution_candidates_and_enforce_status_invariants() {
        MethodTarget first = new MethodTarget(
                new SourceTypeIdentity(
                        new JavaTypeIdentity("example", "Main"),
                        "module-a/src/Main.java"),
                "run",
                List.of());
        MethodTarget second = new MethodTarget(
                new SourceTypeIdentity(
                        new JavaTypeIdentity("example", "Main"),
                        "module-b/src/Main.java"),
                "run",
                List.of());
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
                new SourceTypeIdentity(
                        new JavaTypeIdentity("example", "Main"),
                        "module-a/src/Main.java"),
                "run",
                List.of());

        assertThatIllegalArgumentException().isThrownBy(() -> new MethodTargetResolution(
                AnalysisTargetStatus.AMBIGUOUS, Optional.empty(), List.of(target, target), "DUPLICATE_DECLARATION"));
    }

    @Test
    void should_reject_ambiguous_resolution_when_any_candidate_is_duplicated() {
        MethodTarget first = new MethodTarget(
                new SourceTypeIdentity(
                        new JavaTypeIdentity("example", "Main"),
                        "module-a/src/Main.java"),
                "run",
                List.of());
        MethodTarget second = new MethodTarget(
                new SourceTypeIdentity(
                        new JavaTypeIdentity("example", "Main"),
                        "module-b/src/Main.java"),
                "run",
                List.of());

        assertThatIllegalArgumentException().isThrownBy(() -> new MethodTargetResolution(
                AnalysisTargetStatus.AMBIGUOUS, Optional.empty(), List.of(first, second, first),
                "DUPLICATE_DECLARATION"));
    }
}
