package com.java.semantic.syntax.application.concept;

import java.util.List;
import java.util.Optional;

import com.java.semantic.identity.JavaTypeIdentity;
import com.java.semantic.identity.MethodTarget;
import com.java.semantic.identity.SourceTypeIdentity;
import com.java.semantic.syntax.domain.SourceMemberIdentity.TypeMember;
import com.java.semantic.syntax.application.concept.DeclarationConceptIdentity.FieldConceptIdentity;
import com.java.semantic.syntax.application.concept.DeclarationConceptIdentity.MethodConceptIdentity;
import com.java.semantic.syntax.application.concept.DeclarationConceptIdentity.TypeConceptIdentity;
import com.java.semantic.syntax.application.concept.EntryPointConceptIdentity.ApiRouteConceptIdentity;
import com.java.semantic.syntax.application.concept.EntryPointConceptIdentity.MqDestinationConceptIdentity;
import com.java.semantic.syntax.application.concept.EntryPointConceptIdentity.ScheduleConceptIdentity;
import com.java.semantic.syntax.application.concept.MapperConceptIdentity.MapperStatementConceptIdentity;
import com.java.semantic.syntax.application.concept.MapperConceptIdentity.MapperStatementVariantEvidenceIdentity;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.AnnotationUsageConceptIdentity;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.ResolvedAnnotationIdentity;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.TypeDeclarationSubjectIdentity;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.TypeUsageConceptIdentity;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.TypeUsageLocation;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.TypeUsageSlot;
import com.java.semantic.syntax.domain.MapperEvidenceRepresentation;
import com.java.semantic.syntax.domain.MapperStatementIdentity;
import com.java.semantic.syntax.domain.MapperStatementKey;
import com.java.semantic.syntax.domain.MqBroker;
import com.java.semantic.syntax.domain.ScheduleTriggerKind;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 概念 identity 的精確 discriminator 與型別化排序規則 */
class ConceptIdentityOrderingTest {

    private static final String SOURCE_FILE = "src/main/java/com/example/Order.java";

    private static final JavaTypeIdentity ORDER_TYPE = new JavaTypeIdentity("com.example", "Order");

    @Test
    void should_expose_an_exact_kind_for_every_concrete_identity_variant() {
        MethodTarget target = target();
        MapperStatementIdentity mapperVariant = new MapperStatementIdentity(
                new MapperStatementKey("com.example.OrderMapper", "findOrder"),
                "src/main/resources/mapper/OrderMapper.xml",
                Optional.of("postgres"),
                0,
                MapperEvidenceRepresentation.MAPPER_XML_ELEMENT);
        TypeDeclarationSubjectIdentity owner = new TypeDeclarationSubjectIdentity(sourceType());

        assertThat(List.<ConceptIdentity>of(
                new TypeConceptIdentity(sourceType()),
                new MethodConceptIdentity(target),
                new FieldConceptIdentity(new TypeMember(sourceType(), "id")),
                new AnnotationUsageConceptIdentity(owner, new ResolvedAnnotationIdentity(ORDER_TYPE)),
                new TypeUsageConceptIdentity(
                        owner,
                        new TypeUsageLocation(TypeUsageSlot.FIELD_DECLARATION, 0),
                        TypeUsagePath.empty(),
                        new ReferencedTypeIdentity(ORDER_TYPE, 0)),
                new ApiRouteConceptIdentity(target, "GET", "/orders"),
                new MqDestinationConceptIdentity(target, MqBroker.KAFKA, "orders.created"),
                new ScheduleConceptIdentity(target, ScheduleTriggerKind.CRON, Optional.of("0 0 * * * *")),
                new MapperStatementConceptIdentity(new MapperStatementKey("com.example.OrderMapper", "findOrder")),
                new MapperStatementVariantEvidenceIdentity(mapperVariant)))
                .extracting(ConceptIdentity::identityKind)
                .containsExactly(
                        ConceptIdentityKind.TYPE,
                        ConceptIdentityKind.METHOD,
                        ConceptIdentityKind.FIELD,
                        ConceptIdentityKind.ANNOTATION_USAGE,
                        ConceptIdentityKind.TYPE_USAGE,
                        ConceptIdentityKind.API_ROUTE,
                        ConceptIdentityKind.MQ_DESTINATION,
                        ConceptIdentityKind.SCHEDULE,
                        ConceptIdentityKind.MAPPER_STATEMENT,
                        ConceptIdentityKind.MAPPER_STATEMENT_VARIANT);
    }

    @Test
    void should_order_same_type_usage_paths_by_type_argument_index() {
        TypeDeclarationSubjectIdentity owner = new TypeDeclarationSubjectIdentity(sourceType());
        TypeUsageLocation location = new TypeUsageLocation(TypeUsageSlot.FIELD_DECLARATION, 0);
        ReferencedTypeIdentity referencedType = new ReferencedTypeIdentity(ORDER_TYPE, 0);
        TypeUsageConceptIdentity typeArgumentOne = new TypeUsageConceptIdentity(
                owner,
                location,
                List.of(new TypeUsagePath.TypeArgument(1)),
                referencedType);
        TypeUsageConceptIdentity typeArgumentZero = new TypeUsageConceptIdentity(
                owner,
                location,
                List.of(new TypeUsagePath.TypeArgument(0)),
                referencedType);

        assertThat(List.of(typeArgumentOne, typeArgumentZero).stream()
                .sorted(ConceptIdentityOrdering.comparator())
                .toList())
                .containsExactly(typeArgumentZero, typeArgumentOne);
    }

    private static MethodTarget target() {
        return new MethodTarget(
                sourceType(),
                "findOrder",
                List.of("java.lang.String"));
    }

    private static SourceTypeIdentity sourceType() {
        return new SourceTypeIdentity(ORDER_TYPE, SOURCE_FILE);
    }
}
