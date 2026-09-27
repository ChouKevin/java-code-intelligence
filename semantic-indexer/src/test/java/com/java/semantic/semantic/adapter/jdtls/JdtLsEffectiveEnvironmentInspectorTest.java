package com.java.semantic.semantic.adapter.jdtls;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JdtLsEffectiveEnvironmentInspectorTest {

    private static final List<String> REQUIRED_FIELDS = List.of(
            "org.eclipse.jdt.ls.core.sourcePaths", "classpaths", "modulepaths");

    @Test
    void should_accept_only_explicit_lists_for_required_effective_environment_fields() {
        for (String field : REQUIRED_FIELDS) {
            assertThat(JdtLsEffectiveEnvironmentInspector.requiredStringList(Map.of(field, List.of()), field)).isEmpty();
            assertThatThrownBy(() -> JdtLsEffectiveEnvironmentInspector.requiredStringList(Map.of(), field))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(field);
            assertThatThrownBy(() -> JdtLsEffectiveEnvironmentInspector.requiredStringList(
                    Collections.singletonMap(field, null), field))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(field);
            assertThatThrownBy(() -> JdtLsEffectiveEnvironmentInspector.requiredStringList(Map.of(field, "wrong"), field))
                    .isInstanceOf(IllegalStateException.class);
        }
    }
}
