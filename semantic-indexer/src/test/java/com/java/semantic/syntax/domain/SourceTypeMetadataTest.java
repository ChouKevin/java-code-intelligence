package com.java.semantic.syntax.domain;
import com.java.semantic.model.codefact.SyntaxPosition;
import com.java.semantic.model.codefact.SyntaxRange;
import com.java.semantic.model.codefact.SourceRange;

import com.java.semantic.syntax.domain.SourceTypeKind;

import com.java.semantic.model.codefact.JavaTypeIdentity;
import com.java.semantic.model.codefact.MethodTarget;
import com.java.semantic.model.codefact.SourceTypeIdentity;

import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 驗證來源型別中繼資料的最終權責模型 */
class SourceTypeMetadataTest {

    @Test
    void should_assign_each_type_fact_to_one_final_component() {
        assertThat(SourceTypeMetadata.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .containsExactly("declaration", "relationships", "members", "frameworkFacts", "compilationUnit");
    }

    @Test
    void should_store_type_declarations_as_canonical_source_locations() {
        assertThat(SourceTypeDeclaration.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .containsExactly("identity", "kind", "abstractType", "declarationLocation");
    }

    @Test
    void should_reject_a_source_type_identity_outside_the_repository() {
        assertThatThrownBy(() -> new SourceTypeDeclaration(
                new SourceTypeIdentity(
                        new JavaTypeIdentity("com.example.order", "OrderService"),
                        "../OrderService.java"),
                SourceTypeKind.CLASS,
                false,
                new SourceRange("../OrderService.java",
                        new SyntaxRange(new SyntaxPosition(0, 0), new SyntaxPosition(0, 1)))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void should_reject_a_method_target_from_a_different_source_file_than_its_declaration() {
        SyntaxRange range = new SyntaxRange(new SyntaxPosition(0, 0), new SyntaxPosition(1, 0));
        SourceRange declarationLocation = new SourceRange("src/main/java/com/example/OrderService.java", range);
        MethodTarget target = new MethodTarget(
                new SourceTypeIdentity(
                        new JavaTypeIdentity("com.example", "OrderService"),
                        "src/main/java/com/example/OtherService.java"),
                "process",
                List.of());

        assertThatThrownBy(() -> new SourceMethodMetadata(
                "process", List.of(), null, Optional.empty(), declarationLocation,
                List.of(), Optional.empty(), List.of(), List.of(), List.of(), range.start(),
                MethodTargetResolution.resolved(target), true, false, true, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("analysis target sourceFile must match declarationLocation");
    }
}
