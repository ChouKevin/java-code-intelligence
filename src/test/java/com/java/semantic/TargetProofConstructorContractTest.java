package com.java.semantic;

import com.java.semantic.syntax.domain.ApiEntryPoint;
import com.java.semantic.syntax.domain.ClassMetadata;
import com.java.semantic.syntax.domain.MqEntryPoint;
import com.java.semantic.syntax.domain.MethodTargetResolution;
import com.java.semantic.syntax.domain.ScheduleEntryPoint;
import com.java.semantic.trie.ApiEntryPointRef;
import com.java.semantic.trie.ApiRouteCandidate;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TargetProofConstructorContractTest {

    @Test
    void should_expose_only_canonical_constructors_that_require_an_analysis_target() {
        for (Class<?> targetProofType : new Class<?>[]{
                ApiEntryPoint.class,
                MqEntryPoint.class,
                ScheduleEntryPoint.class,
                ApiEntryPointRef.class,
                ApiRouteCandidate.class}) {
            assertThat(targetProofType.getConstructors())
                    .singleElement()
                    .satisfies(constructor -> {
                    Class<?>[] parameterTypes = constructor.getParameterTypes();
                    assertThat(parameterTypes[parameterTypes.length - 1])
                            .isEqualTo(MethodTargetResolution.class);
                });
        }

        assertThat(ClassMetadata.MethodSignature.class.getConstructors())
                .singleElement()
                .satisfies(constructor -> {
                    assertThat(constructor.getParameterCount())
                            .isEqualTo(ClassMetadata.MethodSignature.class.getRecordComponents().length);
                    assertThat(constructor.getParameterTypes())
                            .contains(MethodTargetResolution.class);
                });
    }
}
