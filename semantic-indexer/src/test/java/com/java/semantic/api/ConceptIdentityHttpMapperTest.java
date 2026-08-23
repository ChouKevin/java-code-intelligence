package com.java.semantic.api;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import com.java.semantic.model.codefact.JavaTypeIdentity;
import com.java.semantic.model.codefact.MethodTarget;
import com.java.semantic.model.codefact.SourceTypeIdentity;
import com.java.semantic.syntax.application.concept.ConceptIdentity;
import com.java.semantic.syntax.application.concept.DeclarationConceptIdentity.FieldConceptIdentity;
import com.java.semantic.syntax.application.concept.DeclarationConceptIdentity.MethodConceptIdentity;
import com.java.semantic.syntax.application.concept.DeclarationConceptIdentity.TypeConceptIdentity;
import com.java.semantic.syntax.application.concept.EntryPointConceptIdentity.ApiRouteConceptIdentity;
import com.java.semantic.syntax.application.concept.EntryPointConceptIdentity.MqDestinationConceptIdentity;
import com.java.semantic.syntax.application.concept.EntryPointConceptIdentity.ScheduleConceptIdentity;
import com.java.semantic.syntax.application.concept.MapperConceptIdentity.MapperStatementConceptIdentity;
import com.java.semantic.syntax.application.concept.MapperConceptIdentity.MapperStatementVariantEvidenceIdentity;
import com.java.semantic.syntax.application.concept.ReferencedTypeIdentity;
import com.java.semantic.syntax.application.concept.TypeUsagePath;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.AnnotationUsageConceptIdentity;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.FieldDeclarationSubjectIdentity;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.ResolvedAnnotationIdentity;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.TypeUsageConceptIdentity;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.TypeUsageLocation;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.TypeUsageSlot;
import com.java.semantic.syntax.domain.MapperEvidenceRepresentation;
import com.java.semantic.syntax.domain.MapperStatementIdentity;
import com.java.semantic.syntax.domain.MapperStatementKey;
import com.java.semantic.syntax.domain.MqBroker;
import com.java.semantic.syntax.domain.ScheduleTriggerKind;
import com.java.semantic.syntax.domain.SourceMemberIdentity.TypeMember;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;

/** 概念 identity HTTP 邊界完整還原契約 */
class ConceptIdentityHttpMapperTest {

    private final ConceptIdentityHttpMapper mapper = new ConceptIdentityHttpMapper(
            new SourceLocationHttpMapper(), new MapperIdentityHttpMapper());

    @ParameterizedTest(name = "{0}")
    @MethodSource("concreteIdentities")
    void should_round_trip_every_concrete_identity_without_losing_typed_coordinates(
            String ignoredCase,
            ConceptIdentity identity) {
        assertThat(mapper.toDomain(mapper.toResponse(identity))).isEqualTo(identity);
    }

    private static Stream<Arguments> concreteIdentities() {
        SourceTypeIdentity nestedType = new SourceTypeIdentity(
                new JavaTypeIdentity("com.example.order", "OrderService.Nested"),
                "src/main/java/com/example/order/OrderService.java");
        SourceTypeIdentity ownerType = new SourceTypeIdentity(
                new JavaTypeIdentity("com.example.order", "OrderService"),
                "src/main/java/com/example/order/OrderService.java");
        MethodTarget target = new MethodTarget(ownerType, "findOrders", List.of("java.lang.String"));
        TypeMember field = new TypeMember(ownerType, "orders");
        MapperStatementKey statement = new MapperStatementKey("com.example.order.OrderMapper", "findOrders");
        MapperStatementIdentity variant = new MapperStatementIdentity(
                statement,
                "src/main/resources/mapper/OrderMapper.xml",
                Optional.of("postgres"),
                2,
                MapperEvidenceRepresentation.MAPPER_XML_ELEMENT);

        return Stream.of(
                Arguments.of("TYPE", new TypeConceptIdentity(nestedType)),
                Arguments.of("METHOD", new MethodConceptIdentity(target)),
                Arguments.of("FIELD", new FieldConceptIdentity(field)),
                Arguments.of("ANNOTATION_USAGE", new AnnotationUsageConceptIdentity(
                        new FieldDeclarationSubjectIdentity(field),
                        new ResolvedAnnotationIdentity(new JavaTypeIdentity(
                                "org.springframework.stereotype", "Service")))),
                Arguments.of("TYPE_USAGE", new TypeUsageConceptIdentity(
                        new FieldDeclarationSubjectIdentity(field),
                        new TypeUsageLocation(TypeUsageSlot.FIELD_DECLARATION, 0),
                        List.of(new TypeUsagePath.TypeArgument(1)),
                        new ReferencedTypeIdentity(new JavaTypeIdentity("com.example.order", "Order"), 1))),
                Arguments.of("API_ROUTE", new ApiRouteConceptIdentity(target, "GET", "/orders")),
                Arguments.of("MQ_DESTINATION", new MqDestinationConceptIdentity(target, MqBroker.KAFKA, "orders.created")),
                Arguments.of("SCHEDULE", new ScheduleConceptIdentity(
                        target, ScheduleTriggerKind.CRON, Optional.of("0 * * * * *"))),
                Arguments.of("MAPPER_STATEMENT", new MapperStatementConceptIdentity(statement)),
                Arguments.of("MAPPER_STATEMENT_VARIANT", new MapperStatementVariantEvidenceIdentity(variant)));
    }
}
