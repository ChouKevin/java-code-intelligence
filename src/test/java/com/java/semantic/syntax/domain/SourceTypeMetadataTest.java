package com.java.semantic.syntax.domain;

import com.java.semantic.syntax.domain.SourceTypeKind;

import com.java.semantic.identity.JavaTypeIdentity;
import com.java.semantic.identity.SourceTypeIdentity;

import java.lang.reflect.RecordComponent;
import java.util.List;

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
    void should_reject_a_source_type_identity_outside_the_repository() {
        assertThatThrownBy(() -> new SourceTypeDeclaration(
                new SourceTypeIdentity(
                        new JavaTypeIdentity("com.example.order", "OrderService"),
                        "../OrderService.java"),
                SourceTypeKind.CLASS,
                false,
                new SourceSlice(new SyntaxRange(new SyntaxPosition(0, 0), new SyntaxPosition(0, 1)),
                        "class OrderService {}")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
