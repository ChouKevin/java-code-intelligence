package com.java.semantic.syntax.application.concept;
import com.java.semantic.syntax.domain.SourceTypeMetadataFixture;
import com.java.semantic.syntax.domain.AnalysisTargetStatus;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.java.semantic.identity.JavaTypeIdentity;
import com.java.semantic.identity.MethodTarget;
import com.java.semantic.identity.SourceTypeIdentity;
import com.java.semantic.syntax.application.concept.DeclarationConceptIdentity.FieldConceptIdentity;
import com.java.semantic.syntax.application.concept.DeclarationConceptIdentity.MethodConceptIdentity;
import com.java.semantic.syntax.application.concept.DeclarationConceptIdentity.TypeConceptIdentity;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.AnnotationUsageConceptIdentity;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.ResolvedAnnotationIdentity;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.TypeUsageConceptIdentity;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.TypeUsageSlot;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.UnresolvedAnnotationIdentity;
import com.java.semantic.syntax.domain.AnnotationEvidence;
import com.java.semantic.syntax.domain.ArrayTypeReference;
import com.java.semantic.syntax.domain.ApiEntryPoint;
import com.java.semantic.syntax.domain.SourceTypeMetadata;
import com.java.semantic.syntax.domain.SourceMemberIdentity.TypeMember;
import com.java.semantic.syntax.domain.SourceTypeRelationships;
import com.java.semantic.syntax.domain.SourceFieldMetadata;
import com.java.semantic.syntax.domain.SourceTypeMembers;
import com.java.semantic.syntax.domain.SourceMethodMetadata;
import com.java.semantic.syntax.domain.SourceTypeKind;
import com.java.semantic.syntax.domain.EntryPointClass;
import com.java.semantic.syntax.domain.MethodTargetResolution;
import com.java.semantic.syntax.domain.MqBroker;
import com.java.semantic.syntax.domain.MqEntryPoint;
import com.java.semantic.syntax.domain.NamedTypeReference;
import com.java.semantic.syntax.domain.RepositorySyntax;
import com.java.semantic.syntax.domain.ScheduleEntryPoint;
import com.java.semantic.syntax.domain.ScheduleTriggerKind;
import com.java.semantic.syntax.domain.SourceRange;
import com.java.semantic.syntax.domain.SyntaxPosition;
import com.java.semantic.syntax.domain.SyntaxRange;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 結構化概念目錄投影規則 */
class StructuredConceptCatalogProjectorTest {

    private final StructuredConceptCatalogProjector projector = new StructuredConceptCatalogProjector();

    @Test
    void should_project_all_phase_one_kinds_with_typed_identity_merge_and_stable_order() {
        StructuredConceptCatalog catalog = projector.project(repositorySyntax());

        assertThat(catalog.entries()).extracting(ConceptCatalogEntry::kind)
                .contains(ConceptKind.TYPE, ConceptKind.METHOD, ConceptKind.FIELD, ConceptKind.ANNOTATION_USAGE,
                        ConceptKind.TYPE_USAGE, ConceptKind.API_ROUTE, ConceptKind.MQ_DESTINATION, ConceptKind.SCHEDULE);
        assertThat(catalog.entries()).filteredOn(entry -> entry.kind() == ConceptKind.TYPE)
                .extracting(ConceptCatalogEntry::identity)
                .containsOnlyOnce(new TypeConceptIdentity(sourceType()));
        assertThat(catalog.entries()).filteredOn(entry -> entry.kind() == ConceptKind.API_ROUTE)
                .extracting(ConceptCatalogEntry::displayValue)
                .containsExactly("OrderHandler.handleOrder", "OrderHandler.handleOrder", "OrderHandler.handleOrder");
        assertThat(catalog.entries()).filteredOn(entry -> entry.kind() == ConceptKind.MQ_DESTINATION)
                .extracting(ConceptCatalogEntry::displayValue)
                .containsExactly("OrderHandler.handleOrder", "OrderHandler.handleOrder");
        assertThat(catalog.entries()).filteredOn(entry -> entry.kind() == ConceptKind.SCHEDULE)
                .extracting(ConceptCatalogEntry::displayValue)
                .containsExactly("OrderHandler.handleOrder", "OrderHandler.handleOrder");
        assertThat(catalog.entries()).filteredOn(entry -> entry.kind() == ConceptKind.SCHEDULE
                        && entry.authority() == ConceptAuthority.METADATA_VALUE_UNRESOLVED)
                .extracting(ConceptCatalogEntry::authority)
                .containsExactly(ConceptAuthority.METADATA_VALUE_UNRESOLVED);
        assertThat(catalog.entries())
                .filteredOn(entry -> entry.identity() instanceof TypeUsageConceptIdentity)
                .extracting(entry -> ((TypeUsageConceptIdentity) entry.identity()).referencedType().fullyQualifiedName())
                .contains("com.acme.order.OrderState[][]");
        assertThat(catalog.entries()).filteredOn(entry -> entry.target().isPresent()
                        && entry.target().orElseThrow().equals(target()))
                .extracting(ConceptCatalogEntry::kind)
                .contains(ConceptKind.METHOD, ConceptKind.API_ROUTE, ConceptKind.MQ_DESTINATION, ConceptKind.SCHEDULE);
        assertThat(catalog.issueSummaries()).containsExactly(
                new ConceptIssueSummary(ConceptIssueReason.MQ_DESTINATION_UNRESOLVED, 3),
                new ConceptIssueSummary(ConceptIssueReason.SCHEDULE_TRIGGER_VALUE_UNRESOLVED, 1));
        assertThat(catalog.entries()).isSortedAccordingTo(ConceptIdentityOrdering.catalogEntryComparator());
    }

    @Test
    void should_project_type_method_and_field_as_declaration_identities_with_existing_values() {
        StructuredConceptCatalog catalog = projector.project(repositorySyntax());

        assertThat(catalog.entries())
                .filteredOn(entry -> Set.of(ConceptKind.TYPE, ConceptKind.METHOD, ConceptKind.FIELD)
                        .contains(entry.kind()))
                .extracting(ConceptCatalogEntry::identity)
                .allMatch(DeclarationConceptIdentity.class::isInstance)
                .containsExactlyInAnyOrder(
                        new TypeConceptIdentity(sourceType()),
                        new MethodConceptIdentity(target()),
                        new FieldConceptIdentity(new TypeMember(sourceType(), "orderState")));
    }

    @Test
    void should_keep_field_type_out_of_identity_and_in_field_details() {
        ConceptCatalogEntry stringField = onlyField(projector.project(new RepositorySyntax(
                List.of(), List.of(metadataWithFieldType("String", "java.lang.String")))));
        ConceptCatalogEntry uuidField = onlyField(projector.project(new RepositorySyntax(
                List.of(), List.of(metadataWithFieldType("UUID", "java.util.UUID")))));

        assertThat(stringField.identity()).isEqualTo(uuidField.identity());
        assertThat(stringField.fieldDetails()).hasValueSatisfying(details ->
                assertThat(details.declaredType()).isEqualTo(namedTypeReference("String", "java.lang.String")));
        assertThat(uuidField.fieldDetails()).hasValueSatisfying(details ->
                assertThat(details.declaredType()).isEqualTo(namedTypeReference("UUID", "java.util.UUID")));

        AnnotationUsageConceptIdentity stringAnnotation = fieldAnnotation(projector.project(new RepositorySyntax(
                List.of(), List.of(metadataWithFieldType("String", "java.lang.String")))));
        AnnotationUsageConceptIdentity uuidAnnotation = fieldAnnotation(projector.project(new RepositorySyntax(
                List.of(), List.of(metadataWithFieldType("UUID", "java.util.UUID")))));
        TypeUsageConceptIdentity stringUsage = fieldTypeUsage(projector.project(new RepositorySyntax(
                List.of(), List.of(metadataWithFieldType("String", "java.lang.String")))));
        TypeUsageConceptIdentity uuidUsage = fieldTypeUsage(projector.project(new RepositorySyntax(
                List.of(), List.of(metadataWithFieldType("UUID", "java.util.UUID")))));

        assertThat(stringAnnotation).isEqualTo(uuidAnnotation);
        assertThat(stringUsage.owner()).isEqualTo(uuidUsage.owner());
    }

    @Test
    void should_project_usage_family_with_empty_paths_and_preserved_annotation_identities() {
        StructuredConceptCatalog catalog = projector.project(repositorySyntax());

        assertThat(catalog.entries())
                .filteredOn(entry -> Set.of(ConceptKind.ANNOTATION_USAGE, ConceptKind.TYPE_USAGE).contains(entry.kind()))
                .extracting(ConceptCatalogEntry::identity)
                .allMatch(UsageConceptIdentity.class::isInstance);
        assertThat(catalog.entries())
                .filteredOn(entry -> entry.identity() instanceof TypeUsageConceptIdentity)
                .extracting(entry -> ((TypeUsageConceptIdentity) entry.identity()).path())
                .allSatisfy(path -> assertThat(path).isSameAs(TypeUsagePath.empty()));
        assertThat(catalog.entries())
                .filteredOn(entry -> entry.identity() instanceof TypeUsageConceptIdentity)
                .extracting(entry -> ((TypeUsageConceptIdentity) entry.identity()).referencedType())
                .contains(new ReferencedTypeIdentity(new JavaTypeIdentity("com.acme.order", "OrderState"), 2));
        assertThat(catalog.entries())
                .filteredOn(entry -> entry.identity() instanceof AnnotationUsageConceptIdentity)
                .extracting(entry -> ((AnnotationUsageConceptIdentity) entry.identity()).annotationIdentity())
                .contains(
                        new UnresolvedAnnotationIdentity("Service"),
                        new ResolvedAnnotationIdentity(new JavaTypeIdentity(
                                "org.springframework.transaction.annotation", "Transactional")));
    }

    @Test
    void should_merge_same_identity_with_different_presentation_independently_of_merge_order() {
        TypeConceptIdentity identity = new TypeConceptIdentity(sourceType());
        TypeConceptIdentity additionalEvidence = new TypeConceptIdentity(new SourceTypeIdentity(
                new JavaTypeIdentity("com.acme.order", "OrderRecord"),
                "src/main/java/com/acme/order/OrderRecord.java"));
        ConceptCatalogEntry left = new ConceptCatalogEntry(
                "z-provider",
                identity,
                "Zulu display",
                "com.acme.zulu",
                Optional.of("com.acme.zulu.ZuluType"),
                ConceptAuthority.SYNTAX_DECLARED,
                Set.of(identity));
        ConceptCatalogEntry right = new ConceptCatalogEntry(
                "a-provider",
                identity,
                "Alpha display",
                "com.acme.alpha",
                Optional.of("com.acme.alpha.AlphaType"),
                ConceptAuthority.FRAMEWORK_METADATA,
                Set.of(identity, additionalEvidence));

        ConceptCatalogEntry mergedFromLeft = left.merge(right);
        ConceptCatalogEntry mergedFromRight = right.merge(left);

        assertThat(mergedFromLeft).isEqualTo(mergedFromRight);
        assertThat(mergedFromLeft)
                .extracting(
                        ConceptCatalogEntry::providerId,
                        ConceptCatalogEntry::displayValue,
                        ConceptCatalogEntry::packageName,
                        ConceptCatalogEntry::declaringType,
                        ConceptCatalogEntry::authority)
                .containsExactly(
                        "a-provider",
                        "Alpha display",
                        "com.acme.alpha",
                        Optional.of("com.acme.alpha.AlphaType"),
                        ConceptAuthority.SYNTAX_DECLARED);
        assertThat(mergedFromLeft.evidence()).containsExactly(identity, additionalEvidence);
    }

    @Test
    void should_project_only_binding_proven_implemented_and_extended_type_usages() {
        SourceTypeMetadata existing = metadata();
        SourceTypeMetadata metadata = new SourceTypeMetadata(
                existing.declaration(),
                new SourceTypeRelationships(
                        List.of(namedTypeReference("BaseHandler", "com.acme.order.BaseHandler")),
                        List.of(namedTypeReference("OrderPort", "com.acme.order.OrderPort"),
                                new NamedTypeReference("MissingPort", "MissingPort", Optional.empty(), false))),
                existing.members(), existing.frameworkFacts(), existing.compilationUnit());

        StructuredConceptCatalog catalog = projector.project(new RepositorySyntax(List.of(), List.of(metadata)));

        assertThat(catalog.entries())
                .filteredOn(entry -> entry.identity() instanceof TypeUsageConceptIdentity)
                .extracting(entry -> (TypeUsageConceptIdentity) entry.identity())
                .filteredOn(identity -> identity.owner().displayValue().equals(OWNER_TYPE))
                .extracting(identity -> identity.location().slot(),
                        identity -> identity.referencedType().fullyQualifiedName())
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(TypeUsageSlot.IMPLEMENTED_TYPE, "com.acme.order.OrderPort"),
                        org.assertj.core.groups.Tuple.tuple(TypeUsageSlot.EXTENDED_TYPE, "com.acme.order.BaseHandler"));
    }

    private static RepositorySyntax repositorySyntax() {
        SourceTypeMetadata metadata = metadata();
        EntryPointClass entryPoints = new EntryPointClass(
                new SourceTypeIdentity(
                        new JavaTypeIdentity("com.acme.order", "OrderHandler"),
                        "com/acme/order/OrderHandler.java"),
                "", List.of(), List.of(
                        new ApiEntryPoint("handleOrder", "", "/orders", List.of("POST", "GET"), List.of(), resolved()),
                        new ApiEntryPoint("handleOrder", "", "/orders", List.of(ApiEntryPoint.ALL_METHODS), List.of(), resolved()),
                        new ApiEntryPoint("handleOrder", "", "/orders/${tenant}", List.of("GET"), List.of(), resolved()),
                        new MqEntryPoint("handleOrder", "", MqBroker.KAFKA,
                                List.of("orders.retry", "orders.created"), resolved()),
                        new MqEntryPoint("handleOrder", "", MqBroker.KAFKA, List.of(), resolved()),
                        new MqEntryPoint("handleOrder", "", MqBroker.KAFKA,
                                List.of("${orders.topic}", "#{topicProvider.topic}"), resolved()),
                        new ScheduleEntryPoint("handleOrder", "", ScheduleTriggerKind.CRON, "0 0 * * * *", resolved()),
                        new ScheduleEntryPoint("handleOrder", "", ScheduleTriggerKind.FIXED_DELAY, "#{delayProvider.value}", resolved())));
        return new RepositorySyntax(List.of(entryPoints), List.of(metadata, metadata));
    }

    private static SourceTypeMetadata metadata() {
        SourceMethodMetadata method = new SourceMethodMetadata(
                "handleOrder",
                List.of("com.acme.order.OrderCommand"),
                null,
                Optional.empty(),
                source(),
                List.of(namedTypeReference("OrderCommand", "com.acme.order.OrderCommand")),
                Optional.of(namedTypeReference("OrderReceipt", "com.acme.order.OrderReceipt")),
                List.of(),
                List.of(annotation("Transactional", "org.springframework.transaction.annotation.Transactional")),
                List.of(new JavaTypeIdentity("com.acme.order", "OrderEvent")),
                new SyntaxPosition(1, 1),
                resolved(),
                true,
                false,
                false);
        SourceFieldMetadata field = new SourceFieldMetadata(
                "orderState",
                "OrderState[][]",
                "",
                new ArrayTypeReference(
                        "OrderState[][]",
                        namedTypeReference("OrderState", "com.acme.order.OrderState"),
                        2),
                List.of(annotation("Autowired", "org.springframework.beans.factory.annotation.Autowired")));
        return SourceTypeMetadataFixture.sourceType(
                "OrderHandler",
                "com.acme.order",
                OWNER_TYPE,
                SOURCE_FILE,
                SourceTypeKind.CLASS,
                false,
                List.of(),
                List.of(),
                List.of("Service"),
                List.of(),
                List.of(field),
                List.of(method),
                false,
                false,
                List.of(),
                range(),
                source(),
                false,
                List.of());
    }

    private static SourceTypeMetadata metadataWithFieldType(String writtenType, String resolvedType) {
        SourceTypeMetadata metadata = metadata();
        SourceFieldMetadata field = new SourceFieldMetadata(
                "orderState",
                writtenType,
                "",
                namedTypeReference(writtenType, resolvedType),
                List.of(annotation("Autowired", "org.springframework.beans.factory.annotation.Autowired")));
        return new SourceTypeMetadata(
                metadata.declaration(),
                metadata.relationships(),
                new SourceTypeMembers(
                        List.of(field),
                        metadata.members().methods(),
                        metadata.members().fluentSetters(),
                        metadata.members().chainedAccessors()),
                metadata.frameworkFacts(),
                metadata.compilationUnit());
    }

    private static ConceptCatalogEntry onlyField(StructuredConceptCatalog catalog) {
        return catalog.entries().stream()
                .filter(entry -> entry.kind() == ConceptKind.FIELD)
                .findFirst()
                .orElseThrow();
    }

    private static AnnotationUsageConceptIdentity fieldAnnotation(StructuredConceptCatalog catalog) {
        return catalog.entries().stream()
                .map(ConceptCatalogEntry::identity)
                .filter(AnnotationUsageConceptIdentity.class::isInstance)
                .map(AnnotationUsageConceptIdentity.class::cast)
                .filter(identity -> identity.annotatedDeclaration() instanceof UsageConceptIdentity.FieldDeclarationSubjectIdentity)
                .findFirst()
                .orElseThrow();
    }

    private static TypeUsageConceptIdentity fieldTypeUsage(StructuredConceptCatalog catalog) {
        return catalog.entries().stream()
                .map(ConceptCatalogEntry::identity)
                .filter(TypeUsageConceptIdentity.class::isInstance)
                .map(TypeUsageConceptIdentity.class::cast)
                .filter(identity -> identity.owner() instanceof UsageConceptIdentity.FieldDeclarationSubjectIdentity)
                .findFirst()
                .orElseThrow();
    }

    private static AnnotationEvidence annotation(String writtenName, String resolvedType) {
        return new AnnotationEvidence(writtenName, Optional.of(new JavaTypeIdentity(
                resolvedType.substring(0, resolvedType.lastIndexOf('.')),
                resolvedType.substring(resolvedType.lastIndexOf('.') + 1))));
    }

    private static NamedTypeReference namedTypeReference(String writtenType, String resolvedType) {
        int lastDot = resolvedType.lastIndexOf('.');
        String packageName = resolvedType.substring(0, lastDot);
        String className = resolvedType.substring(lastDot + 1);
        return new NamedTypeReference(
                writtenType,
                className,
                Optional.of(new JavaTypeIdentity(packageName, className)),
                true);
    }

    private static MethodTargetResolution resolved() {
        return MethodTargetResolution.resolved(target());
    }

    private static MethodTarget target() {
        return new MethodTarget(
                sourceType(),
                "handleOrder",
                List.of("com.acme.order.OrderCommand"));
    }

    private static SourceTypeIdentity sourceType() {
        return new SourceTypeIdentity(new JavaTypeIdentity("com.acme.order", "OrderHandler"), SOURCE_FILE);
    }

    private static SyntaxRange range() {
        return new SyntaxRange(new SyntaxPosition(0, 0), new SyntaxPosition(0, 1));
    }

    private static SourceRange source() {
        return new SourceRange(SOURCE_FILE, range());
    }

    private static final String SOURCE_FILE = "src/main/java/com/acme/order/OrderHandler.java";

    private static final String OWNER_TYPE = "com.acme.order.OrderHandler";
}
